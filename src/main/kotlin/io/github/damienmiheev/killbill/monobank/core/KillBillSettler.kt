/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import io.github.damienmiheev.killbill.monobank.config.SystemSettings
import io.github.damienmiheev.killbill.monobank.db.TransactionDao
import io.github.damienmiheev.killbill.monobank.db.TxRow
import io.github.damienmiheev.killbill.monobank.db.TxState
import org.joda.time.DateTime
import org.killbill.billing.ErrorCode
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI
import org.killbill.billing.payment.api.Payment
import org.killbill.billing.payment.api.PaymentApiException
import org.killbill.billing.payment.api.PaymentOptions
import org.killbill.billing.payment.api.TransactionStatus
import org.killbill.billing.util.callcontext.CallContext
import org.killbill.billing.util.callcontext.CallOrigin
import org.killbill.billing.util.callcontext.UserType
import org.slf4j.LoggerFactory
import java.util.UUID

/** Brings Kill Bill in line with a final row; swapped for a recorder in tests. */
fun interface TransactionSettler {
    fun settle(row: TxRow)

    /** Kill Bill's final status of a row held for review, once an operator has fixed it there; null while open. */
    fun reviewedStatus(row: TxRow): TxState? = null
}

/**
 * Brings Kill Bill in line with a row that has reached a final state (design A6):
 * - a PENDING Kill Bill transaction is completed through its payment control plugins, so an invoice payment
 *   marks the invoice paid (the plain notify call would leave it unpaid);
 * - an UNKNOWN one is repaired by Kill Bill's own on-the-fly Janitor, by reading the payment with plugin info;
 * - the same final state already in Kill Bill only marks the row settled;
 * - the opposite final state is never overwritten: it is logged and counted as a conflict for an operator.
 */

class KillBillSettler(
    private val killbill: OSGIKillbillAPI,
    private val settings: SystemSettings,
    private val transactions: TransactionDao,
    private val metrics: PluginMetrics,
    private val now: () -> DateTime,
) : TransactionSettler {
    override fun settle(row: TxRow) {
        if (!row.state.isFinal || row.state == TxState.CANCELED || row.kbSettledAt != null) return
        asPlugin(row.tenantId) {
            val context = context(row)
            val payment = payment(row.paymentId, withPluginInfo = false, context) ?: return@asPlugin
            val kbTransaction = payment.transactions.firstOrNull { it.id == row.transactionId } ?: return@asPlugin
            when (kbTransaction.transactionStatus) {
                TransactionStatus.PENDING -> completePending(row, payment, context)
                TransactionStatus.UNKNOWN -> repairUnknown(row, context)
                else -> compare(row, kbTransaction.transactionStatus)
            }
        }
    }

    private fun completePending(
        row: TxRow,
        payment: Payment,
        context: CallContext,
    ) {
        val account = killbill.accountUserApi.getAccountById(row.accountId, context)
        // Only this transaction's own attempt says which control plugins ran it (a refund or a capture has none
        // even when the purchase had them); a payment made without the control API has no attempt at all.
        val controlPlugins =
            payment.paymentAttempts
                ?.lastOrNull { it.transactionId == row.transactionId }
                ?.pluginName
                ?.split(',')
                ?.filter {
                    it.isNotBlank()
                }.orEmpty()
        val success = row.state == TxState.SUCCESS
        try {
            if (controlPlugins.isEmpty()) {
                killbill.paymentApi.notifyPendingTransactionOfStateChanged(account, row.transactionId, success, context)
            } else {
                killbill.paymentApi.notifyPendingTransactionOfStateChangedWithPaymentControl(
                    account,
                    row.transactionId,
                    success,
                    Options(controlPlugins),
                    context,
                )
            }
            transactions.markSettled(row.recordId)
            LOG.info(
                "Settled Kill Bill transaction {} as {} (control plugins {})",
                row.transactionId,
                row.state,
                controlPlugins,
            )
        } catch (e: PaymentApiException) {
            if (e.code == ErrorCode.PAYMENT_NO_SUCH_SUCCESS_PAYMENT.code) {
                // Someone (Kill Bill's Janitor) settled it in between: compare on the next run.
                LOG.info("Kill Bill transaction {} was no longer pending", row.transactionId)
            } else {
                throw e
            }
        }
    }

    override fun reviewedStatus(row: TxRow): TxState? {
        if (row.state != TxState.REVIEW) return null
        return asPlugin(row.tenantId) {
            val payment = payment(row.paymentId, withPluginInfo = false, context(row)) ?: return@asPlugin null
            when (payment.transactions.firstOrNull { it.id == row.transactionId }?.transactionStatus) {
                TransactionStatus.SUCCESS -> TxState.SUCCESS
                TransactionStatus.PAYMENT_FAILURE, TransactionStatus.PLUGIN_FAILURE -> TxState.FAILED
                else -> null
            }
        }
    }

    private fun repairUnknown(
        row: TxRow,
        context: CallContext,
    ) {
        // Reading with plugin info runs Kill Bill's on-the-fly Janitor, which asks getPaymentInfo and fixes the state.
        val repaired = payment(row.paymentId, withPluginInfo = true, context) ?: return
        val status = repaired.transactions.firstOrNull { it.id == row.transactionId }?.transactionStatus ?: return
        if (status != TransactionStatus.UNKNOWN) compare(row, status)
    }

    private fun compare(
        row: TxRow,
        kbStatus: TransactionStatus,
    ) {
        val expected =
            if (row.state ==
                TxState.SUCCESS
            ) {
                TransactionStatus.SUCCESS
            } else {
                TransactionStatus.PAYMENT_FAILURE
            }
        if (kbStatus == expected || (row.state == TxState.FAILED && kbStatus == TransactionStatus.PLUGIN_FAILURE)) {
            transactions.markSettled(row.recordId)
            return
        }
        metrics.stateConflict.incrementAndGet()
        LOG.error(
            "state_conflict: Kill Bill transaction {} is {} but monobank says {} (invoice {}); " +
                "not overwritten, needs an operator",
            row.transactionId,
            kbStatus,
            row.state,
            row.invoiceId,
        )
        transactions.markSettled(row.recordId)
    }

    private fun payment(
        paymentId: UUID,
        withPluginInfo: Boolean,
        context: CallContext,
    ): Payment? =
        try {
            killbill.paymentApi.getPayment(paymentId, withPluginInfo, true, emptyList(), context)
        } catch (e: PaymentApiException) {
            if (e.code == ErrorCode.PAYMENT_NO_SUCH_PAYMENT.code) null else throw e
        }

    /** Runs [block] logged in as the plugin's own Kill Bill user (permission `payment:transition` only). */
    private fun <T> asPlugin(
        tenantId: UUID,
        block: () -> T,
    ): T {
        val security = killbill.securityApi
        security.login(settings.kbUsername, settings.kbPassword.reveal())
        try {
            return block()
        } finally {
            runCatching { security.logout() }
            LOG.trace("Logged out after settling for tenant {}", tenantId)
        }
    }

    private fun context(row: TxRow): CallContext = PluginCallContext(row.tenantId, row.accountId, now())

    private class Options(
        private val names: List<String>,
    ) : PaymentOptions {
        override fun isExternalPayment(): Boolean = false

        override fun getPaymentControlPluginNames(): List<String> = names
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(KillBillSettler::class.java)
    }
}

/** The call context the plugin uses for its own calls into Kill Bill. */
class PluginCallContext(
    private val tenantId: UUID,
    private val accountId: UUID?,
    private val at: DateTime,
) : CallContext {
    private val token = UUID.randomUUID()

    override fun getUserToken(): UUID = token

    override fun getUserName(): String = "killbill-monobank"

    override fun getCallOrigin(): CallOrigin = CallOrigin.EXTERNAL

    override fun getUserType(): UserType = UserType.SYSTEM

    override fun getReasonCode(): String? = null

    override fun getComments(): String? = null

    override fun getCreatedDate(): DateTime = at

    override fun getUpdatedDate(): DateTime = at

    override fun getAccountId(): UUID? = accountId

    override fun getTenantId(): UUID = tenantId
}
