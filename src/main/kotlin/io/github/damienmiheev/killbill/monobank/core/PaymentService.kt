/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import io.github.damienmiheev.killbill.monobank.config.TenantSettings
import io.github.damienmiheev.killbill.monobank.config.TenantSettingsProvider
import io.github.damienmiheev.killbill.monobank.crypto.CardTokenCipher
import io.github.damienmiheev.killbill.monobank.crypto.CardTokenUnreadableException
import io.github.damienmiheev.killbill.monobank.db.CardStatus
import io.github.damienmiheev.killbill.monobank.db.Claim
import io.github.damienmiheev.killbill.monobank.db.NewTx
import io.github.damienmiheev.killbill.monobank.db.PaymentMethodDao
import io.github.damienmiheev.killbill.monobank.db.PmRow
import io.github.damienmiheev.killbill.monobank.db.TransactionDao
import io.github.damienmiheev.killbill.monobank.db.TxChange
import io.github.damienmiheev.killbill.monobank.db.TxRow
import io.github.damienmiheev.killbill.monobank.db.TxState
import io.github.damienmiheev.killbill.monobank.money.InvalidAmountException
import io.github.damienmiheev.killbill.monobank.money.Money
import io.github.damienmiheev.killbill.monobank.mono.MonobankClients
import io.github.damienmiheev.monobank.acquiring.MonobankAcquiring
import io.github.damienmiheev.monobank.acquiring.common.AcquiringErrorCodes
import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.FinalizeInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.InvoiceStatusResponse
import io.github.damienmiheev.monobank.acquiring.invoice.PaymentResponse
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest
import io.github.damienmiheev.monobank.core.MonobankApiException
import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.core.MonobankException
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import io.github.damienmiheev.monobank.core.RequestOutcome
import org.killbill.billing.catalog.api.Currency
import org.killbill.billing.payment.api.TransactionType
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One Kill Bill payment operation, as the plugin API receives it. */
data class Operation(
    val type: TransactionType,
    val tenantId: UUID,
    val accountId: UUID,
    val paymentId: UUID,
    val transactionId: UUID,
    val paymentMethodId: UUID?,
    val amount: BigDecimal?,
    val currency: Currency?,
    val properties: Properties,
)

/** What the plugin answers Kill Bill: either a stored row or a refusal that never reached monobank. */
sealed interface Answer {
    data class Stored(
        val row: TxRow,
    ) : Answer

    data class Refused(
        val op: Operation,
        val code: String,
        val message: String,
        /** A decline of the payment (PAYMENT_FAILURE) rather than a plugin problem (PLUGIN_FAILURE). */
        val decline: Boolean = false,
    ) : Answer
}

/**
 * The payment logic of design A4–A7: one monobank operation per Kill Bill transaction, idempotent by the
 * transaction id, never reporting an unknown outcome as failed, and moving rows only forward.
 */
@Suppress("TooManyFunctions", "LargeClass")
class PaymentService(
    private val transactions: TransactionDao,
    private val paymentMethods: PaymentMethodDao,
    private val clients: MonobankClients,
    private val tenants: TenantSettingsProvider,
    private val cipher: CardTokenCipher,
    private val metrics: PluginMetrics,
    private val now: () -> Instant,
) {
    // ---- charges --------------------------------------------------------------------------------------------

    /** PURCHASE or AUTHORIZE: hosted page when the caller asks for it, otherwise the saved card. */
    fun charge(op: Operation): Answer {
        // Kill Bill may call again for the same transaction (a retry, PUT /payments/{id}): never charge twice.
        transactions.findByTransaction(op.transactionId)?.let { return Answer.Stored(refreshIfOpen(it)) }
        val plan =
            when (val checked = checkCharge(op)) {
                is ChargeRefusal -> return checked.answer
                is ChargePlan -> checked
            }
        val row =
            when (val claim = transactions.claim(plan.newTx(op))) {
                is Claim.Existing -> return Answer.Stored(refreshIfOpen(claim.row))

                Claim.Unresolved -> return refused(
                    op,
                    PREVIOUS_CHARGE_UNRESOLVED,
                    "an earlier charge on this account is unresolved",
                )

                is Claim.Claimed -> claim.row
            }
        val client = clients.forToken(plan.settings.merchantToken)
        val info =
            MerchantPaymInfo
                .builder()
                .reference(row.reference.toString())
                .destination(plan.destination)
                .basketOrder(plan.basket)
                .build()
        return Answer.Stored(
            if (plan.hosted) {
                createHosted(row, plan.money, plan.settings, op, client, info)
            } else {
                chargeSavedCard(row, plan.card!!, plan.money, plan.settings, op, client, info)
            },
        )
    }

    private sealed interface ChargeCheck

    private class ChargeRefusal(
        val answer: Answer,
    ) : ChargeCheck

    /** A checked charge, ready to claim. */
    private class ChargePlan(
        val settings: TenantSettings,
        val money: Money,
        val destination: String,
        val basket: List<BasketItem>?,
        /** The plugin's own one-unit item, not the caller's basketOrder. */
        val defaultBasket: Boolean,
        val hosted: Boolean,
        val card: PmRow?,
    ) : ChargeCheck {
        fun newTx(op: Operation): NewTx =
            NewTx(
                tenantId = op.tenantId,
                accountId = op.accountId,
                paymentId = op.paymentId,
                paymentMethodId = op.paymentMethodId,
                invoiceKbId = op.properties.uuid(Properties.KB_INVOICE_ID),
                transactionId = op.transactionId,
                type = op.type,
                callerRef = op.properties.string(Properties.CALLER_REF),
                invoiceId = null,
                amountMinor = money.minor,
                currency = money.currency.currencyCode,
                saveCard = hosted && op.properties.flag(Properties.SAVE_CARD),
                basket = Baskets.toJson(basket, isDefault = defaultBasket),
            )
    }

    /** Every refusal of a charge before anything is stored or sent: a [ChargePlan] or the refusal [Answer]. */
    private fun checkCharge(op: Operation): ChargeCheck {
        val settings =
            settings(op)
                ?: return ChargeRefusal(refused(op, CONFIG_INVALID, "the tenant's monobank settings are not valid"))
        // monobank converts USD and EUR to hryvnia when paid and cannot refund such saved-card payments in full (seen
        // live), so the plugin takes UAH only; accounts in other currencies use another payment plugin.
        if (op.currency != null && op.currency.name != UAH) {
            return ChargeRefusal(
                refused(op, CURRENCY_NOT_SUPPORTED, "monobank payments are UAH only, not ${op.currency.name}"),
            )
        }
        val money = money(op) ?: return ChargeRefusal(refused(op, INVALID_AMOUNT, "invalid amount or currency"))
        val destination = op.properties.string(Properties.DESTINATION) ?: settings.destination ?: DEFAULT_DESTINATION
        val callerBasket = op.properties.string(Properties.BASKET_ORDER)
        val basket =
            try {
                Baskets.fromProperty(callerBasket, money.minor) ?: Baskets.single(settings, destination, money.minor)
            } catch (e: IllegalArgumentException) {
                return ChargeRefusal(refused(op, INVALID_BASKET, e.message ?: "invalid basketOrder"))
            }
        val validity = op.properties.long(Properties.VALIDITY_SECONDS)
        if (op.properties.string(Properties.VALIDITY_SECONDS) != null &&
            (validity == null || validity !in VALIDITY_RANGE)
        ) {
            return ChargeRefusal(refused(op, INVALID_REQUEST, "validitySeconds must be from 60 to 2592000"))
        }
        val hosted = op.properties.flag(Properties.HOSTED)
        val card = op.paymentMethodId?.let(paymentMethods::find)
        if (!hosted && (card == null || card.cardStatus != CardStatus.ACTIVE)) {
            return ChargeRefusal(
                refused(op, NO_ACTIVE_CARD, "the payment method has no saved card; use hosted=true", decline = true),
            )
        }
        return ChargePlan(settings, money, destination, basket, callerBasket == null, hosted, card)
    }

    private fun createHosted(
        row: TxRow,
        money: Money,
        settings: TenantSettings,
        op: Operation,
        client: MonobankAcquiring,
        info: MerchantPaymInfo,
    ): TxRow {
        val validity = op.properties.long(Properties.VALIDITY_SECONDS) ?: settings.checkoutValiditySeconds
        val request =
            CreateInvoiceRequest
                .builder(money.minor)
                .ccy(money.numericCode)
                .merchantPaymInfo(info)
                .redirectUrl(op.properties.string(Properties.REDIRECT_URL) ?: settings.redirectUrl?.toString())
                .webHookUrl(settings.webhookUrl.toString())
                .validity(validity)
                .paymentType(if (row.type == TransactionType.AUTHORIZE) PaymentType.HOLD else PaymentType.DEBIT)
                .saveCardData(
                    if (row.saveCard) {
                        SaveCardData.saveTo(walletId(row))
                    } else {
                        null
                    },
                ).build()
        val stored =
            call(row) {
                val created = client.invoices.create(request)
                TxChange(
                    state = TxState.PENDING,
                    invoiceId = created.invoiceId,
                    monoStatus = InvoiceStatus.CREATED.value,
                    formUrl = created.pageUrl,
                    expiresAt = now().plusSeconds(validity),
                    nextCheckAt = now().plusSeconds(validity).plus(EXPIRY_MARGIN),
                )
            }
        // After the row is stored, so a failure here cannot leave the charge without its invoice id.
        if (row.saveCard && row.paymentMethodId != null && stored.invoiceId != null) {
            try {
                paymentMethods.markPending(row.paymentMethodId, stored.invoiceId)
            } catch (e: java.sql.SQLException) {
                LOG.warn(
                    "Could not mark the card of payment method {} as pending; polling will catch up",
                    row.paymentMethodId,
                    e,
                )
            }
        }
        return stored
    }

    private fun chargeSavedCard(
        row: TxRow,
        pm: PmRow,
        money: Money,
        settings: TenantSettings,
        op: Operation,
        client: MonobankAcquiring,
        info: MerchantPaymInfo,
    ): TxRow {
        val token =
            try {
                cipher.open(
                    CardTokenCipher.Sealed(pm.tokenKeyId!!, pm.tokenCiphertext!!),
                    pm.tenantId,
                    pm.paymentMethodId,
                )
            } catch (e: CardTokenUnreadableException) {
                LOG.error("Card token of payment method {} cannot be read: {}", pm.paymentMethodId, e.message)
                return transactions.transition(
                    row.recordId,
                    setOf(TxState.SENDING),
                    TxChange(
                        TxState.CANCELED,
                        errorCode = CARD_UNREADABLE,
                        errorMessage = "the saved card cannot be read",
                    ),
                )
            }
        val initiation =
            if (op.properties.string(Properties.INITIATION_KIND).equals("client", ignoreCase = true)) {
                InitiationKind.CLIENT
            } else {
                InitiationKind.MERCHANT
            }
        val request =
            WalletPaymentRequest
                .builder(token, money.minor, money.numericCode, initiation)
                .merchantPaymInfo(info)
                .webHookUrl(settings.webhookUrl.toString())
                .redirectUrl(op.properties.string(Properties.REDIRECT_URL) ?: settings.redirectUrl?.toString())
                .paymentType(if (row.type == TransactionType.AUTHORIZE) PaymentType.HOLD else PaymentType.DEBIT)
                .build()
        return call(row, onRejected = { e -> if (e.errorCode == AcquiringErrorCodes.TOKEN_NOT_FOUND) retireCard(pm) }) {
            val startedAt = System.nanoTime()
            val response = client.wallet.payment(request)
            paymentChange(row, response, declineCode(response, client, startedAt))
        }
    }

    /** monobank's decline code: the payment answer rarely has it, the invoice status always does. */
    private fun declineCode(
        response: PaymentResponse,
        client: MonobankAcquiring,
        startedAt: Long,
    ): String? {
        if (response.status != OperationStatus.FAILURE) return null
        response.errCode?.let { return it }
        // Only when one more call surely fits in Kill Bill's 30 s; otherwise the decline stays DECLINED.
        if (Duration.ofNanos(System.nanoTime() - startedAt).plus(clients.requestTimeout) > ANSWER_BUDGET) return null
        val invoiceId = response.invoiceId
        return try {
            client.invoices.status(invoiceId).errCode
        } catch (e: MonobankException) {
            LOG.info("Could not read the decline code of invoice {} ({})", invoiceId, e.outcome)
            null
        }
    }

    private fun paymentChange(
        row: TxRow,
        response: PaymentResponse,
        declineCode: String? = null,
    ): TxChange {
        val change =
            when (response.status) {
                OperationStatus.SUCCESS -> {
                    TxChange(TxState.SUCCESS, processedAmountMinor = response.amount, settled = true)
                }

                OperationStatus.FAILURE -> {
                    TxChange(
                        TxState.FAILED,
                        errorCode = declineCode ?: response.errCode ?: DECLINED,
                        errorMessage = response.failureReason,
                        settled = true,
                    )
                }

                else -> {
                    TxChange(
                        TxState.PENDING,
                        tdsUrl = response.tdsUrl,
                        expiresAt = response.tdsUrl?.let { now().plus(THREE_DS_VALIDITY) },
                        nextCheckAt = now().plus(FIRST_CHECK),
                    )
                }
            }
        return change
            .copy(
                invoiceId = response.invoiceId,
                monoStatus = response.statusRaw,
                monoModifiedAt = response.modifiedDate?.toInstant(),
            ).let {
                if (response.amount != row.amountMinor ||
                    !Money.isSameCurrency(response.ccy, Money.currency(row.currency))
                ) {
                    review(row, "monobank reports ${response.amount}/${response.ccy}", it)
                } else {
                    it
                }
            }
    }

    private fun retireCard(pm: PmRow) {
        paymentMethods.setStatus(pm.paymentMethodId, CardStatus.FAILED, setOf(CardStatus.ACTIVE))
        LOG.warn("monobank no longer knows the card of payment method {}; it is retired", pm.paymentMethodId)
    }

    // ---- capture, void, refund ------------------------------------------------------------------------------

    /** A follow-up operation on an existing charge: what to send, or why not. */
    private sealed interface FollowUp {
        data class Refuse(
            val code: String,
            val message: String,
        ) : FollowUp

        class Send(
            val money: Money,
            val invoiceId: String,
            /** The receipt items sent, stored on the row so a refund after a capture can repeat them. */
            val basket: String? = null,
            val call: (TxRow, MonobankAcquiring) -> TxChange,
        ) : FollowUp
    }

    fun capture(op: Operation): Answer =
        followUp(op) { settings ->
            val money =
                followUpMoney(op) ?: return@followUp FollowUp.Refuse(INVALID_AMOUNT, "invalid amount or currency")
            val rows = transactions.findByPayment(op.paymentId)
            val auth = rows.firstOrNull { it.type == TransactionType.AUTHORIZE }
            val refusal =
                when {
                    auth == null || auth.state != TxState.SUCCESS || auth.invoiceId == null -> NO_HOLD

                    rows.any {
                        it.type in CAPTURE_OR_VOID && it.state != TxState.CANCELED && it.state != TxState.FAILED
                    } -> "the hold was already captured or voided"

                    auth.currency != money.currency.currencyCode -> "capture currency differs from the authorization"

                    money.minor > auth.amountMinor -> "capture amount is above the hold"

                    // from when monobank made the hold (a hosted page may be paid days after it was created)
                    (auth.monoModifiedAt ?: auth.createdAt)
                        .plus(HOLD_LIFETIME)
                        .isBefore(now()) -> "the hold has lapsed (monobank keeps it 9 days)"

                    else -> null
                }
            if (refusal != null) return@followUp FollowUp.Refuse(INVALID_OPERATION, refusal)
            val items =
                when (val checked = operationItems(op, auth?.basket, money)) {
                    is ItemsCheck.Invalid -> return@followUp FollowUp.Refuse(INVALID_BASKET, checked.reason)
                    is ItemsCheck.Valid -> checked.items
                }
            FollowUp.Send(money, auth!!.invoiceId!!, Baskets.storedFor(items, auth.basket)) { _, client ->
                val response =
                    client.invoices.finalize(
                        FinalizeInvoiceRequest
                            .builder(auth.invoiceId, money.minor)
                            .items(items?.let(Baskets::fiscal))
                            .build(),
                    )
                operationChange(response.status, response.statusRaw, money.minor)
            }
        }

    fun void(op: Operation): Answer =
        followUp(op) { _ ->
            val rows = transactions.findByPayment(op.paymentId)
            val auth = rows.firstOrNull { it.type == TransactionType.AUTHORIZE }
            val captured =
                rows.any {
                    it.type in CAPTURE_OR_VOID && it.state != TxState.FAILED && it.state != TxState.CANCELED
                }
            val refusal =
                when {
                    rows.any { it.type == TransactionType.PURCHASE } -> "a purchase cannot be voided; use a refund"
                    auth == null || auth.state != TxState.SUCCESS || auth.invoiceId == null -> NO_HOLD
                    captured -> "the authorization was already captured or voided"
                    else -> null
                }
            if (refusal != null) return@followUp FollowUp.Refuse(INVALID_OPERATION, refusal)
            FollowUp.Send(Money(auth!!.amountMinor, Money.currency(auth.currency)), auth.invoiceId!!) { row, client ->
                val response =
                    client.invoices.cancel(
                        CancelInvoiceRequest.builder(auth.invoiceId).extRef(row.reference.toString()).build(),
                    )
                operationChange(response.status, response.statusRaw, auth.amountMinor)
            }
        }

    fun refund(op: Operation): Answer =
        followUp(op) { settings ->
            val money =
                followUpMoney(op) ?: return@followUp FollowUp.Refuse(INVALID_AMOUNT, "invalid amount or currency")
            val rows = transactions.findByPayment(op.paymentId)
            val charge =
                rows.firstOrNull { it.type == TransactionType.PURCHASE && it.state == TxState.SUCCESS }
                    ?: rows.firstOrNull { it.type == TransactionType.CAPTURE && it.state == TxState.SUCCESS }
            val refundable = charge?.let { it.processedAmountMinor ?: it.amountMinor } ?: 0L
            val refunded =
                rows
                    .filter {
                        it.type == TransactionType.REFUND && it.state in REFUND_COUNTS
                    }.sumOf { it.amountMinor }
            val refusal =
                when {
                    charge?.invoiceId == null -> "there is no successful charge to refund"
                    charge.currency != money.currency.currencyCode -> "refund currency differs from the charge"
                    money.minor > refundable - refunded -> "refund is above what is left to refund"
                    else -> null
                }
            if (refusal != null) return@followUp FollowUp.Refuse(INVALID_OPERATION, refusal)
            val items =
                // the basket of the charge that took the money: the purchase, or the capture of a hold
                when (val checked = operationItems(op, charge?.basket, money)) {
                    is ItemsCheck.Invalid -> return@followUp FollowUp.Refuse(INVALID_BASKET, checked.reason)
                    is ItemsCheck.Valid -> checked.items
                }
            FollowUp.Send(money, charge!!.invoiceId!!, Baskets.storedFor(items, charge.basket)) { row, client ->
                // The amount is always sent, so a partly captured hold is never refunded by monobank's own guess.
                val request =
                    CancelInvoiceRequest
                        .builder(
                            charge.invoiceId,
                        ).extRef(row.reference.toString())
                        .amount(money.minor)
                        .items(items?.let(Baskets::fiscal))
                val response = client.invoices.cancel(request.build())
                operationChange(response.status, response.statusRaw, money.minor)
            }
        }

    private sealed interface ItemsCheck {
        class Valid(
            /** Null when the charge sent no items. */
            val items: List<BasketItem>?,
        ) : ItemsCheck

        class Invalid(
            val reason: String,
        ) : ItemsCheck
    }

    /**
     * Receipt items for a capture or refund (empty when the charge sent none), built from the charge's own items:
     * monobank refuses an item that is not in the charge's basket and a partial amount without items on a merchant
     * with receipts or split payments (both seen live).
     */
    private fun operationItems(
        op: Operation,
        chargeBasket: String?,
        money: Money,
    ): ItemsCheck =
        try {
            val json = op.properties.string(Properties.BASKET_ORDER)
            ItemsCheck.Valid(Baskets.forOperation(json, chargeBasket, money.minor))
        } catch (e: IllegalArgumentException) {
            LOG.info("Refused {} for transaction {}: {}", op.type, op.transactionId, e.message)
            ItemsCheck.Invalid(e.message ?: "invalid basketOrder")
        }

    /** Idempotency, settings, the plan, the claim and the single call, shared by capture, void and refund. */
    private fun followUp(
        op: Operation,
        plan: (TenantSettings) -> FollowUp,
    ): Answer {
        transactions.findByTransaction(op.transactionId)?.let { return Answer.Stored(refreshIfOpen(it)) }
        val settings =
            settings(op) ?: return refused(op, CONFIG_INVALID, "the tenant's monobank settings are not valid")
        return when (val next = plan(settings)) {
            is FollowUp.Refuse -> {
                refused(op, next.code, next.message)
            }

            is FollowUp.Send -> {
                when (val claim = claimFollowUp(op, next.money, next.invoiceId, next.basket)) {
                    Claim.Unresolved -> {
                        refused(op, PREVIOUS_OPERATION_UNRESOLVED, UNRESOLVED_MESSAGE)
                    }

                    // Another call holds this transaction: never call monobank for it a second time.
                    is Claim.Existing -> {
                        Answer.Stored(refreshIfOpen(claim.row))
                    }

                    is Claim.Claimed -> {
                        Answer.Stored(
                            call(claim.row) { next.call(claim.row, clients.forToken(settings.merchantToken)) },
                        )
                    }
                }
            }
        }
    }

    private fun claimFollowUp(
        op: Operation,
        money: Money,
        invoiceId: String?,
        basket: String?,
    ): Claim =
        transactions.claim(
            NewTx(
                tenantId = op.tenantId,
                accountId = op.accountId,
                paymentId = op.paymentId,
                paymentMethodId = op.paymentMethodId,
                invoiceKbId = null,
                transactionId = op.transactionId,
                type = op.type,
                callerRef = op.properties.string(Properties.CALLER_REF),
                invoiceId = invoiceId,
                amountMinor = money.minor,
                currency = money.currency.currencyCode,
                saveCard = false,
                basket = basket,
            ),
        )

    private fun operationChange(
        status: OperationStatus,
        raw: String,
        processed: Long,
    ): TxChange =
        when (status) {
            OperationStatus.SUCCESS -> {
                TxChange(
                    TxState.SUCCESS,
                    monoStatus = raw,
                    processedAmountMinor = processed,
                    settled = true,
                )
            }

            OperationStatus.FAILURE -> {
                TxChange(TxState.FAILED, monoStatus = raw, errorCode = DECLINED, settled = true)
            }

            else -> {
                TxChange(TxState.PENDING, monoStatus = raw, nextCheckAt = now().plus(FIRST_CHECK))
            }
        }

    // ---- giving up an unpaid hosted page -----------------------------------------------------------------------

    /** What happened to a request to give up a hosted payment page (design A7, `POST /payments/{id}/cancel`). */
    sealed interface Cancellation {
        data class Done(
            val row: TxRow,
        ) : Cancellation

        /** The customer paid (or is paying): nothing was cancelled, the payment settles as paid. */
        data class AlreadyPaid(
            val row: TxRow,
        ) : Cancellation

        data object NotFound : Cancellation

        data class Unavailable(
            val reason: String,
        ) : Cancellation
    }

    /** Removes monobank's invoice of an unpaid hosted payment; monobank refuses when the customer has paid. */
    fun cancelHosted(
        paymentId: UUID,
        tenantId: UUID,
    ): Cancellation {
        val row =
            transactions
                .findByPayment(paymentId)
                .firstOrNull { it.tenantId == tenantId && it.formUrl != null && it.invoiceId != null }
                ?: return Cancellation.NotFound
        val settings =
            (tenants.get(tenantId) as? TenantSettings.ParseResult.Valid)?.settings
                ?: return Cancellation.Unavailable("the tenant's monobank settings are not valid")
        if (row.state.isFinal || row.state == TxState.REVIEW) return outcomeOf(row)
        val client = clients.forToken(settings.merchantToken)
        var removed = true
        try {
            client.invoices.remove(row.invoiceId!!)
        } catch (e: MonobankApiException) {
            if (e.outcome != RequestOutcome.REJECTED) return Cancellation.Unavailable("monobank did not answer")
            removed = false
            LOG.info("monobank refused to remove invoice {} ({}): it is paid or being paid", row.invoiceId, e.errorCode)
        } catch (e: MonobankException) {
            return Cancellation.Unavailable("monobank did not answer (${e.outcome})")
        }
        try {
            apply(client.invoices.status(row.invoiceId!!), tenantId)
        } catch (e: MonobankNotFoundException) {
            // A removed invoice may no longer be found: removal is monobank's answer that nobody paid it.
            LOG.info("monobank no longer finds invoice {} ({})", row.invoiceId, e.errorCode)
            if (!removed) return Cancellation.Unavailable("monobank does not know invoice ${row.invoiceId}")
            transactions.transition(
                row.recordId,
                TxState.OPEN,
                TxChange(
                    TxState.FAILED,
                    errorCode = CANCELED_BY_MERCHANT,
                    errorMessage = "the payment page was given up",
                ),
            )
        } catch (e: MonobankException) {
            return Cancellation.Unavailable("monobank did not answer (${e.outcome})")
        }
        return outcomeOf(transactions.findByRecord(row.recordId)!!)
    }

    private fun outcomeOf(row: TxRow): Cancellation =
        when {
            row.state == TxState.FAILED || row.state == TxState.CANCELED -> Cancellation.Done(row)
            row.state == TxState.SUCCESS || row.monoStatus in PAID_OR_PAYING -> Cancellation.AlreadyPaid(row)
            else -> Cancellation.Unavailable("monobank has not settled the page yet; ask again")
        }

    // ---- calling monobank -----------------------------------------------------------------------------------

    /**
     * Calls monobank once for a claimed (SENDING) row and records the result (design A4 step 3–4). An outcome that
     * may have reached monobank becomes UNKNOWN, never FAILED; a request that was not sent becomes CANCELED.
     */
    private fun call(
        row: TxRow,
        onRejected: (MonobankApiException) -> Unit = {},
        block: () -> TxChange,
    ): TxRow {
        val started = System.nanoTime()
        var from = TxState.OPEN
        val result =
            try {
                block().also { metrics.lastMonobankCall.set(now()) }
            } catch (e: MonobankException) {
                if (e is MonobankApiException && e.outcome == RequestOutcome.REJECTED) onRejected(e)
                // A webhook may have moved the row on while the call ran: a failure never overwrites it.
                from = setOf(TxState.SENDING)
                failureChange(e, row.type)
            } catch (e: Exception) {
                // A bug or an answer the plugin could not read: the request may have reached monobank.
                LOG.error("Unexpected failure calling monobank for transaction {}", row.transactionId, e)
                from = setOf(TxState.SENDING)
                TxChange(TxState.UNKNOWN, errorCode = OUTCOME_UNKNOWN, nextCheckAt = now().plus(FIRST_CHECK))
            }
        // Kill Bill gives up on a plugin call after 30 s and keeps it UNKNOWN: then it has not learnt this answer.
        val inTime = Duration.ofNanos(System.nanoTime() - started) < ANSWER_BUDGET
        val change = if (inTime) result else result.copy(settled = false)
        return try {
            val stored = transactions.transition(row.recordId, from, change)
            // A webhook got there first with the same answer: Kill Bill learns it from this call.
            if (change.settled && stored.state == change.state && stored.kbSettledAt == null) {
                transactions.markSettled(stored.recordId)
                transactions.findByRecord(stored.recordId)!!
            } else {
                stored
            }
        } catch (e: java.sql.SQLException) {
            // monobank may have acted but nothing is stored: answer UNKNOWN, never a retryable failure. The row stays
            // SENDING, blocks a second charge, and the reconcile job finds the outcome.
            LOG.error("Could not store monobank's answer for transaction {}", row.transactionId, e)
            row.copy(state = TxState.UNKNOWN, errorCode = OUTCOME_UNKNOWN)
        }
    }

    private fun failureChange(
        e: MonobankException,
        type: TransactionType,
    ): TxChange =
        when (e.outcome ?: RequestOutcome.UNKNOWN) {
            RequestOutcome.NOT_SENT -> {
                TxChange(
                    TxState.CANCELED,
                    errorCode = NOT_SENT,
                    errorMessage = "monobank could not be reached",
                    settled = true,
                )
            }

            RequestOutcome.REJECTED -> {
                val api = e as? MonobankApiException
                val code = api?.errorCode ?: REJECTED
                val decline = code in DECLINE_CODES
                if (e is MonobankAuthException) {
                    LOG.error(
                        "monobank refused the merchant token (403): check the tenant's settings",
                    )
                }
                // monobank settles a partial capture for a while ("processing"); a refund in that window is refused
                // with 1004 "order not finished" and nothing is refunded: CANCELED, so the caller simply retries.
                // 1004 is monobank's generic "not found" too, so only that exact refusal of a refund is relabelled.
                val settling =
                    type == TransactionType.REFUND && code == ORDER_NOT_FINISHED &&
                        api?.errorText.equals(ORDER_NOT_FINISHED_TEXT, ignoreCase = true)
                TxChange(
                    if (decline) TxState.FAILED else TxState.CANCELED,
                    errorCode = if (settling) CAPTURE_SETTLING else code,
                    errorMessage =
                        if (settling) {
                            "monobank is still settling the capture; nothing was refunded, try again in a minute " +
                                "(${api?.errorText})"
                        } else {
                            api?.errorText ?: e.message
                        },
                    settled = true,
                )
            }

            RequestOutcome.UNKNOWN -> {
                LOG.warn(
                    "monobank call outcome unknown ({}); the transaction stays UNKNOWN until monobank tells",
                    e.javaClass.simpleName,
                )
                TxChange(TxState.UNKNOWN, errorCode = OUTCOME_UNKNOWN, nextCheckAt = now().plus(FIRST_CHECK))
            }
        }

    // ---- reading monobank's state ---------------------------------------------------------------------------

    /** Refreshes an open row that has a monobank invoice from `invoice/status`; returns the row as stored now. */
    fun refreshIfOpen(row: TxRow): TxRow {
        if (row.state !in TxState.OPEN || row.invoiceId == null) return row
        val settings = (tenants.get(row.tenantId) as? TenantSettings.ParseResult.Valid)?.settings ?: return row
        try {
            apply(clients.forToken(settings.merchantToken).invoices.status(row.invoiceId), row.tenantId)
        } catch (e: MonobankException) {
            LOG.info("Could not refresh invoice {} from monobank: {}", row.invoiceId, e.outcome)
            return row
        } catch (e: Exception) {
            // Never fail Kill Bill's read because of one row: the stored state is still true.
            LOG.error("Could not apply monobank's state of invoice {}", row.invoiceId, e)
            return row
        }
        return transactions.findByRecord(row.recordId)!!
    }

    /** What applying one monobank invoice status changed. */
    data class Applied(
        /** Rows that are final now and were not SENDING before (their Kill Bill transaction can be settled). */
        val toSettle: List<TxRow>,
        val matched: Boolean,
    )

    /**
     * Applies an invoice status (from a webhook or a poll) to every row of that invoice, the card it saved and its
     * refunds, each part on its own and only forward (design A7 step 6).
     */
    fun apply(
        status: InvoiceStatusResponse,
        tenantId: UUID,
    ): Applied {
        val rows = transactions.findByInvoice(status.invoiceId).filter { it.tenantId == tenantId }
        val byReference =
            status.reference
                ?.let { ref ->
                    runCatching { UUID.fromString(ref) }.getOrNull()
                }?.let(transactions::findByReference)
        val charge =
            rows.firstOrNull { it.type == TransactionType.PURCHASE || it.type == TransactionType.AUTHORIZE }
                ?: byReference?.takeIf {
                    it.tenantId == tenantId &&
                        (it.invoiceId == null || it.invoiceId == status.invoiceId)
                }
        if (charge == null) return Applied(emptyList(), matched = false)
        val toSettle = mutableListOf<TxRow>()
        val before = charge.state
        if (before == TxState.FAILED && status.status in setOf(InvoiceStatus.SUCCESS, InvoiceStatus.HOLD)) {
            metrics.stateConflict.incrementAndGet()
            LOG.error(
                "state_conflict: transaction {} was closed as FAILED but monobank now says {} (invoice {}); " +
                    "needs an operator",
                charge.transactionId,
                status.statusRaw,
                status.invoiceId,
            )
        }
        val after = transactions.transition(charge.recordId, TxState.OPEN, chargeChange(charge, status))
        if (after.state.isFinal && before != TxState.SENDING && after.state != before) toSettle += after
        applyCapture(rows, status, toSettle)
        applyCancels(rows, status, toSettle)
        applyCard(status, tenantId)
        return Applied(toSettle, matched = true)
    }

    private fun chargeChange(
        row: TxRow,
        status: InvoiceStatusResponse,
    ): TxChange {
        val base =
            TxChange(
                state = row.state,
                invoiceId = status.invoiceId,
                monoStatus = status.statusRaw,
                monoModifiedAt = status.modifiedDate?.toInstant(),
            )
        val paidState = if (row.type == TransactionType.AUTHORIZE) InvoiceStatus.HOLD else InvoiceStatus.SUCCESS
        val target =
            when (status.status) {
                // A hold already captured (SUCCESS) or released (REVERSED) when first seen was held:
                // the AUTHORIZE succeeded.
                paidState, InvoiceStatus.SUCCESS, InvoiceStatus.REVERSED -> {
                    if (!sameMoney(
                            row,
                            status,
                        )
                    ) {
                        return review(
                            row,
                            "monobank reports ${status.amount}/${status.ccy}",
                        ).copy(invoiceId = status.invoiceId)
                    }
                    base.copy(state = TxState.SUCCESS, processedAmountMinor = status.amount)
                }

                InvoiceStatus.FAILURE -> {
                    base.copy(
                        state = TxState.FAILED,
                        errorCode = status.errCode ?: DECLINED,
                        errorMessage = status.failureReason,
                    )
                }

                InvoiceStatus.EXPIRED -> {
                    base.copy(
                        state = TxState.FAILED,
                        errorCode = EXPIRED,
                        errorMessage = "the payment page expired unpaid",
                    )
                }

                InvoiceStatus.HOLD -> {
                    review(row, "a purchase invoice is held")
                }

                else -> {
                    base.copy(state = TxState.PENDING, nextCheckAt = row.nextCheckAt ?: now().plus(FIRST_CHECK))
                }
            }
        return target
    }

    private fun applyCapture(
        rows: List<TxRow>,
        status: InvoiceStatusResponse,
        toSettle: MutableList<TxRow>,
    ) {
        val capture = rows.firstOrNull { it.type == TransactionType.CAPTURE && it.state in TxState.OPEN } ?: return
        val change =
            when (status.status) {
                InvoiceStatus.SUCCESS -> {
                    TxChange(
                        TxState.SUCCESS,
                        processedAmountMinor =
                            status.finalAmount ?: capture.amountMinor,
                    )
                }

                InvoiceStatus.REVERSED, InvoiceStatus.FAILURE -> {
                    TxChange(TxState.FAILED, errorCode = "HOLD_RELEASED")
                }

                else -> {
                    return
                }
            }
        settleLater(capture, change, toSettle)
    }

    private fun applyCancels(
        rows: List<TxRow>,
        status: InvoiceStatusResponse,
        toSettle: MutableList<TxRow>,
    ) {
        val ours = rows.filter { it.type == TransactionType.REFUND || it.type == TransactionType.VOID }
        for (row in ours.filter { it.state in TxState.OPEN }) {
            val item = status.cancelFor(row.reference.toString())
            if (item == null) {
                // A released hold may be reported only as the invoice's own status.
                if (row.type == TransactionType.VOID && status.status == InvoiceStatus.REVERSED) {
                    settleLater(row, TxChange(TxState.SUCCESS, monoStatus = status.statusRaw), toSettle)
                }
                continue
            }
            val change =
                when (item.status) {
                    OperationStatus.SUCCESS -> {
                        TxChange(
                            TxState.SUCCESS,
                            monoStatus = item.statusRaw,
                            processedAmountMinor = item.amount,
                        )
                    }

                    OperationStatus.FAILURE -> {
                        TxChange(
                            TxState.FAILED,
                            monoStatus = item.statusRaw,
                            errorCode = DECLINED,
                        )
                    }

                    else -> {
                        continue
                    }
                }
            settleLater(row, change, toSettle)
        }
        val known = ours.map { it.reference.toString() }.toSet()
        val strangers = status.cancelList.orEmpty().filter { it.extRef == null || it.extRef !in known }
        if (strangers.isNotEmpty()) {
            metrics.unexpectedReversal.incrementAndGet()
            LOG.error(
                "unexpected_reversal: invoice {} has {} cancel(s) the plugin did not start " +
                    "(portal refund or chargeback)",
                status.invoiceId,
                strangers.size,
            )
        }
    }

    private fun settleLater(
        row: TxRow,
        change: TxChange,
        toSettle: MutableList<TxRow>,
    ) {
        val before = row.state
        val after = transactions.transition(row.recordId, TxState.OPEN, change)
        if (after.state.isFinal && before != TxState.SENDING && after.state != before) toSettle += after
    }

    private fun applyCard(
        status: InvoiceStatusResponse,
        tenantId: UUID,
    ) {
        val wallet = status.walletData ?: return
        for (pm in paymentMethods.findBySourceInvoice(status.invoiceId).filter { it.tenantId == tenantId }) {
            val token = wallet.cardToken
            when {
                wallet.isCardSaved && token != null -> {
                    val sealed = cipher.seal(token, pm.tenantId, pm.paymentMethodId)
                    if (paymentMethods.activate(
                            pm.paymentMethodId,
                            sealed.ciphertext,
                            sealed.keyId,
                            cipher.hmac(token),
                            wallet.maskedPan ?: status.paymentInfo?.maskedPan,
                            status.paymentInfo?.paymentSystemRaw,
                            wallet.country ?: status.paymentInfo?.country,
                        )
                    ) {
                        LOG.info("Card saved on payment method {}", pm.paymentMethodId)
                    }
                }

                wallet.statusRaw == "failed" -> {
                    paymentMethods.setStatus(
                        pm.paymentMethodId,
                        CardStatus.FAILED,
                        setOf(CardStatus.PENDING, CardStatus.NONE),
                    )
                }
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private fun walletId(row: TxRow): String = row.accountId.toString()

    private fun sameMoney(
        row: TxRow,
        status: InvoiceStatusResponse,
    ): Boolean = status.amount == row.amountMinor && Money.isSameCurrency(status.ccy, Money.currency(row.currency))

    /** REVIEW keeps what monobank said (invoice id, status, time) so an operator can find the payment. */
    private fun review(
        row: TxRow,
        why: String,
        base: TxChange = TxChange(TxState.REVIEW),
    ): TxChange {
        metrics.amountMismatch.incrementAndGet()
        LOG.error("amount_mismatch: transaction {} needs review: {}", row.transactionId, why)
        return base.copy(state = TxState.REVIEW, errorCode = NEEDS_REVIEW, errorMessage = why, settled = false)
    }

    private fun settings(op: Operation): TenantSettings? =
        (tenants.get(op.tenantId) as? TenantSettings.ParseResult.Valid)?.settings

    private fun money(op: Operation): Money? {
        val amount = op.amount ?: return null
        val currency = op.currency ?: return null
        return try {
            Money.ofMajor(amount, Money.currency(currency.name)).takeIf { it.minor > 0 }
        } catch (e: InvalidAmountException) {
            LOG.info("Refused {} for transaction {}: {}", op.type, op.transactionId, e.message)
            null
        }
    }

    /** A follow-up's amount: any currency with a minor unit; it must match the parent row, not today's settings. */
    private fun followUpMoney(op: Operation): Money? {
        val amount = op.amount ?: return null
        val currency = op.currency ?: return null
        return try {
            Money.ofMajor(amount, Money.currency(currency.name)).takeIf { it.minor > 0 }
        } catch (e: InvalidAmountException) {
            LOG.info("Refused {} for transaction {}: {}", op.type, op.transactionId, e.message)
            null
        }
    }

    private fun refused(
        op: Operation,
        code: String,
        message: String,
        decline: Boolean = false,
    ): Answer = Answer.Refused(op, code, message, decline)

    companion object {
        private val LOG = LoggerFactory.getLogger(PaymentService::class.java)

        const val CONFIG_INVALID = "CONFIG_INVALID"
        const val INVALID_AMOUNT = "INVALID_AMOUNT"
        const val INVALID_OPERATION = "INVALID_OPERATION"
        const val NO_ACTIVE_CARD = "NO_ACTIVE_CARD"
        const val PREVIOUS_CHARGE_UNRESOLVED = "PREVIOUS_CHARGE_UNRESOLVED"
        const val PREVIOUS_OPERATION_UNRESOLVED = "PREVIOUS_OPERATION_UNRESOLVED"
        const val CARD_UNREADABLE = "CARD_UNREADABLE"
        const val NOT_SENT = "NOT_SENT"
        const val REJECTED = "REJECTED"
        const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
        const val DECLINED = "DECLINED"
        const val EXPIRED = "EXPIRED"
        const val NEEDS_REVIEW = "NEEDS_REVIEW"
        const val INVALID_BASKET = "INVALID_BASKET"
        const val CANCELED_BY_MERCHANT = "CANCELED_BY_MERCHANT"
        const val CAPTURE_SETTLING = "CAPTURE_SETTLING"
        const val CURRENCY_NOT_SUPPORTED = "CURRENCY_NOT_SUPPORTED"
        private const val ORDER_NOT_FINISHED = "1004"
        private const val ORDER_NOT_FINISHED_TEXT = "order not finished"

        /** Below Kill Bill's 30 s plugin call, with room to store the answer. */
        private val ANSWER_BUDGET: Duration = Duration.ofSeconds(28)
        private val PAID_OR_PAYING = setOf("processing", "success", "hold")

        private const val UAH = "UAH"
        private val CAPTURE_OR_VOID = setOf(TransactionType.CAPTURE, TransactionType.VOID)
        private val VALIDITY_RANGE = 60L..2_592_000L
        const val INVALID_REQUEST = "INVALID_REQUEST"
        private const val NO_HOLD = "there is no held authorization"
        private const val DEFAULT_DESTINATION = "Payment"
        private const val UNRESOLVED_MESSAGE = "an earlier operation on this payment is unresolved"
        private val DECLINE_CODES = setOf(AcquiringErrorCodes.TOKEN_NOT_FOUND)
        private val REFUND_COUNTS =
            setOf(TxState.SUCCESS, TxState.PENDING, TxState.SENDING, TxState.UNKNOWN, TxState.REVIEW)
        val FIRST_CHECK: Duration = Duration.ofMinutes(2)
        val EXPIRY_MARGIN: Duration = Duration.ofMinutes(5)
        val THREE_DS_VALIDITY: Duration = Duration.ofHours(1)
        val HOLD_LIFETIME: Duration = Duration.ofDays(9)
    }
}
