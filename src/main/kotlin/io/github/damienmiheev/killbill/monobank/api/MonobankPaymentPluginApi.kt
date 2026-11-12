/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.api

import io.github.damienmiheev.killbill.monobank.core.Answer
import io.github.damienmiheev.killbill.monobank.core.CardService
import io.github.damienmiheev.killbill.monobank.core.Operation
import io.github.damienmiheev.killbill.monobank.core.PaymentService
import io.github.damienmiheev.killbill.monobank.core.Properties
import io.github.damienmiheev.killbill.monobank.db.PmRow
import io.github.damienmiheev.killbill.monobank.db.TransactionDao
import io.github.damienmiheev.killbill.monobank.db.TxRow
import io.github.damienmiheev.killbill.monobank.money.Money
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.killbill.billing.catalog.api.Currency
import org.killbill.billing.payment.api.PaymentMethodPlugin
import org.killbill.billing.payment.api.PluginProperty
import org.killbill.billing.payment.api.TransactionType
import org.killbill.billing.payment.plugin.api.GatewayNotification
import org.killbill.billing.payment.plugin.api.HostedPaymentPageFormDescriptor
import org.killbill.billing.payment.plugin.api.PaymentMethodInfoPlugin
import org.killbill.billing.payment.plugin.api.PaymentPluginApi
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin
import org.killbill.billing.util.callcontext.CallContext
import org.killbill.billing.util.callcontext.TenantContext
import org.killbill.billing.util.entity.Pagination
import org.killbill.clock.Clock
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** The Kill Bill payment API of the plugin: translates Kill Bill's calls into [PaymentService] operations. */
@Suppress("TooManyFunctions")
class MonobankPaymentPluginApi(
    private val payments: PaymentService,
    private val cards: CardService,
    private val transactions: TransactionDao,
    private val clock: Clock,
) : PaymentPluginApi {
    override fun authorizePayment(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        kbPaymentMethodId: UUID?,
        amount: BigDecimal?,
        currency: Currency?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): PaymentTransactionInfoPlugin =
        answer(
            payments.charge(
                op(
                    TransactionType.AUTHORIZE,
                    kbAccountId,
                    kbPaymentId,
                    kbTransactionId,
                    kbPaymentMethodId,
                    amount,
                    currency,
                    properties,
                    context,
                ),
            ),
        )

    override fun capturePayment(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        kbPaymentMethodId: UUID?,
        amount: BigDecimal?,
        currency: Currency?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): PaymentTransactionInfoPlugin =
        answer(
            payments.capture(
                op(
                    TransactionType.CAPTURE,
                    kbAccountId,
                    kbPaymentId,
                    kbTransactionId,
                    kbPaymentMethodId,
                    amount,
                    currency,
                    properties,
                    context,
                ),
            ),
        )

    override fun purchasePayment(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        kbPaymentMethodId: UUID?,
        amount: BigDecimal?,
        currency: Currency?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): PaymentTransactionInfoPlugin =
        answer(
            payments.charge(
                op(
                    TransactionType.PURCHASE,
                    kbAccountId,
                    kbPaymentId,
                    kbTransactionId,
                    kbPaymentMethodId,
                    amount,
                    currency,
                    properties,
                    context,
                ),
            ),
        )

    override fun voidPayment(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        kbPaymentMethodId: UUID?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): PaymentTransactionInfoPlugin =
        answer(
            payments.void(
                op(
                    TransactionType.VOID,
                    kbAccountId,
                    kbPaymentId,
                    kbTransactionId,
                    kbPaymentMethodId,
                    null,
                    null,
                    properties,
                    context,
                ),
            ),
        )

    override fun creditPayment(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        kbPaymentMethodId: UUID?,
        amount: BigDecimal?,
        currency: Currency?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): PaymentTransactionInfoPlugin =
        TransactionResult(
            kbPaymentId,
            kbTransactionId,
            TransactionType.CREDIT,
            amount,
            currency,
            PaymentPluginStatus.CANCELED,
            UNSUPPORTED,
            "monobank does not support credits",
            clock.utcNow,
        )

    override fun refundPayment(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        kbPaymentMethodId: UUID?,
        amount: BigDecimal?,
        currency: Currency?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): PaymentTransactionInfoPlugin =
        answer(
            payments.refund(
                op(
                    TransactionType.REFUND,
                    kbAccountId,
                    kbPaymentId,
                    kbTransactionId,
                    kbPaymentMethodId,
                    amount,
                    currency,
                    properties,
                    context,
                ),
            ),
        )

    /** Every transaction of the payment, refreshed from monobank when not final (Kill Bill's Janitor relies on it). */
    override fun getPaymentInfo(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        properties: Iterable<PluginProperty>?,
        context: TenantContext?,
    ): List<PaymentTransactionInfoPlugin> {
        val paymentId = kbPaymentId ?: return emptyList()
        return transactions
            .findByPayment(paymentId)
            .filter { context?.tenantId == null || it.tenantId == context.tenantId }
            .map { result(payments.refreshIfOpen(it)) }
    }

    override fun searchPayments(
        searchKey: String?,
        offset: Long?,
        limit: Long?,
        properties: Iterable<PluginProperty>?,
        context: TenantContext?,
    ): Pagination<PaymentTransactionInfoPlugin> = EmptyPagination(offset)

    override fun addPaymentMethod(
        kbAccountId: UUID?,
        kbPaymentMethodId: UUID?,
        paymentMethodProps: PaymentMethodPlugin?,
        setDefault: Boolean,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ) {
        cards.add(
            require(context?.tenantId, "tenant"),
            require(kbAccountId, "account"),
            require(kbPaymentMethodId, "payment method"),
        )
    }

    override fun deletePaymentMethod(
        kbAccountId: UUID?,
        kbPaymentMethodId: UUID?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ) {
        cards.delete(require(kbPaymentMethodId, "payment method"))
    }

    override fun getPaymentMethodDetail(
        kbAccountId: UUID?,
        kbPaymentMethodId: UUID?,
        properties: Iterable<PluginProperty>?,
        context: TenantContext?,
    ): PaymentMethodPlugin {
        val id = require(kbPaymentMethodId, "payment method")
        return CardDetail(id, cards.find(id))
    }

    override fun setDefaultPaymentMethod(
        kbAccountId: UUID?,
        kbPaymentMethodId: UUID?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ) {
        // Kill Bill owns the default payment method; the plugin keeps no copy of it.
    }

    override fun getPaymentMethods(
        kbAccountId: UUID?,
        refreshFromGateway: Boolean,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): List<PaymentMethodInfoPlugin> {
        val accountId = kbAccountId ?: return emptyList()
        return cards.byAccount(accountId).map { CardInfo(it) }
    }

    override fun searchPaymentMethods(
        searchKey: String?,
        offset: Long?,
        limit: Long?,
        properties: Iterable<PluginProperty>?,
        context: TenantContext?,
    ): Pagination<PaymentMethodPlugin> = EmptyPagination(offset)

    override fun resetPaymentMethods(
        kbAccountId: UUID?,
        paymentMethods: List<PaymentMethodInfoPlugin>?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ) {
        // Nothing is imported from monobank that the plugin did not save itself.
    }

    override fun buildFormDescriptor(
        kbAccountId: UUID?,
        customFields: Iterable<PluginProperty>?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): HostedPaymentPageFormDescriptor =
        throw PaymentPluginApiException(
            UNSUPPORTED,
            "use a PURCHASE or AUTHORIZE with the plugin property hosted=true; it returns PENDING with formUrl",
        )

    override fun processNotification(
        notification: String?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): GatewayNotification =
        throw PaymentPluginApiException(
            UNSUPPORTED,
            "monobank webhooks go to POST /plugins/killbill-monobank/notification",
        )

    // ---- mapping --------------------------------------------------------------------------------------------

    @Suppress("LongParameterList")
    private fun op(
        type: TransactionType,
        accountId: UUID?,
        paymentId: UUID?,
        transactionId: UUID?,
        paymentMethodId: UUID?,
        amount: BigDecimal?,
        currency: Currency?,
        properties: Iterable<PluginProperty>?,
        context: TenantContext?,
    ): Operation =
        Operation(
            type = type,
            tenantId = require(context?.tenantId, "tenant"),
            accountId = require(accountId, "account"),
            paymentId = require(paymentId, "payment"),
            transactionId = require(transactionId, "transaction"),
            paymentMethodId = paymentMethodId,
            amount = amount,
            currency = currency,
            properties = Properties(properties),
        )

    private fun answer(answer: Answer): PaymentTransactionInfoPlugin =
        when (answer) {
            is Answer.Stored -> {
                result(answer.row)
            }

            is Answer.Refused -> {
                TransactionResult(
                    answer.op.paymentId,
                    answer.op.transactionId,
                    answer.op.type,
                    answer.op.amount,
                    answer.op.currency,
                    if (answer.decline) PaymentPluginStatus.ERROR else PaymentPluginStatus.CANCELED,
                    answer.code,
                    answer.message,
                    clock.utcNow,
                )
            }
        }

    private fun result(row: TxRow): PaymentTransactionInfoPlugin {
        val properties =
            listOfNotNull(
                row.formUrl?.let { PluginProperty(Properties.FORM_URL, it, false) },
                row.tdsUrl?.let { PluginProperty(Properties.TDS_URL, it, false) },
                row.invoiceId?.let { PluginProperty(Properties.INVOICE_ID, it, false) },
                row.expiresAt?.let { PluginProperty(Properties.EXPIRES_AT, it.toString(), false) },
                row.callerRef?.let { PluginProperty(Properties.CALLER_REF, it, false) },
                PluginProperty(Properties.REFERENCE, row.reference.toString(), false),
            )
        val currency = Money.currency(row.currency)
        return TransactionResult(
            row.paymentId,
            row.transactionId,
            row.type,
            Money(row.processedAmountMinor ?: row.amountMinor, currency).toMajor(),
            Currency.valueOf(row.currency),
            row.state.pluginStatus,
            row.errorCode,
            row.errorMessage,
            row.createdAt.toDateTime(),
            row.invoiceId,
            row.reference.toString(),
            properties,
            effectiveAt = row.updatedAt.toDateTime(),
        )
    }

    private fun <T : Any> require(
        value: T?,
        what: String,
    ): T = value ?: throw PaymentPluginApiException(INVALID_REQUEST, "Kill Bill passed no $what")

    private fun Instant.toDateTime(): DateTime = DateTime(toEpochMilli(), DateTimeZone.UTC)

    companion object {
        const val UNSUPPORTED = "UNSUPPORTED"
        const val INVALID_REQUEST = "INVALID_REQUEST"
    }
}

/** A transaction result as Kill Bill reads it. */
class TransactionResult(
    private val kbPaymentId: UUID?,
    private val kbTransactionId: UUID?,
    private val type: TransactionType,
    private val amount: BigDecimal?,
    private val currency: Currency?,
    private val status: PaymentPluginStatus,
    private val errorCode: String?,
    private val error: String?,
    private val at: DateTime,
    private val firstReference: String? = null,
    private val secondReference: String? = null,
    private val properties: List<PluginProperty> = emptyList(),
    private val effectiveAt: DateTime = at,
) : PaymentTransactionInfoPlugin {
    override fun getKbPaymentId(): UUID? = kbPaymentId

    override fun getKbTransactionPaymentId(): UUID? = kbTransactionId

    override fun getTransactionType(): TransactionType = type

    override fun getAmount(): BigDecimal? = amount

    override fun getCurrency(): Currency? = currency

    override fun getCreatedDate(): DateTime = at

    override fun getEffectiveDate(): DateTime = effectiveAt

    override fun getStatus(): PaymentPluginStatus = status

    override fun getGatewayError(): String? = error

    override fun getGatewayErrorCode(): String? = errorCode

    override fun getFirstPaymentReferenceId(): String? = firstReference

    override fun getSecondPaymentReferenceId(): String? = secondReference

    override fun getProperties(): List<PluginProperty> = properties
}

/** A saved card as Kill Bill shows it: masked details only, never the token. */
private class CardDetail(
    private val paymentMethodId: UUID,
    private val row: PmRow?,
) : PaymentMethodPlugin {
    override fun getKbPaymentMethodId(): UUID = paymentMethodId

    override fun getExternalPaymentMethodId(): String? = row?.let { "monobank-card-${it.recordId}" }

    override fun isDefaultPaymentMethod(): Boolean = false

    override fun getProperties(): List<PluginProperty> =
        listOfNotNull(
            row?.let { PluginProperty("cardStatus", it.cardStatus.name, false) },
            row?.maskedPan?.let { PluginProperty("maskedPan", it, false) },
            row?.cardBrand?.let { PluginProperty("cardBrand", it, false) },
            row?.cardCountry?.let { PluginProperty("cardCountry", it, false) },
        )
}

private class CardInfo(
    private val row: PmRow,
) : PaymentMethodInfoPlugin {
    override fun getAccountId(): UUID = row.accountId

    override fun getPaymentMethodId(): UUID = row.paymentMethodId

    override fun isDefault(): Boolean = false

    override fun getExternalPaymentMethodId(): String = "monobank-card-${row.recordId}"
}

private class EmptyPagination<T>(
    private val offset: Long?,
) : Pagination<T> {
    override fun getCurrentOffset(): Long = offset ?: 0L

    override fun getNextOffset(): Long? = null

    override fun getMaxNbRecords(): Long = 0L

    override fun getTotalNbRecords(): Long = 0L

    override fun iterator(): MutableIterator<T> = mutableListOf<T>().iterator()

    override fun close() = Unit
}
