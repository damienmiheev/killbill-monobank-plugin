/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.reconcile

import io.github.damienmiheev.killbill.monobank.config.SystemSettings
import io.github.damienmiheev.killbill.monobank.config.TenantSettings
import io.github.damienmiheev.killbill.monobank.config.TenantSettingsProvider
import io.github.damienmiheev.killbill.monobank.core.CardService
import io.github.damienmiheev.killbill.monobank.core.PaymentService
import io.github.damienmiheev.killbill.monobank.core.PluginMetrics
import io.github.damienmiheev.killbill.monobank.core.TransactionSettler
import io.github.damienmiheev.killbill.monobank.db.NotificationDao
import io.github.damienmiheev.killbill.monobank.db.PaymentMethodDao
import io.github.damienmiheev.killbill.monobank.db.TransactionDao
import io.github.damienmiheev.killbill.monobank.db.TxChange
import io.github.damienmiheev.killbill.monobank.db.TxRow
import io.github.damienmiheev.killbill.monobank.db.TxState
import io.github.damienmiheev.killbill.monobank.mono.MonobankClients
import io.github.damienmiheev.monobank.acquiring.MonobankAcquiring
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.core.MonobankApiException
import io.github.damienmiheev.monobank.core.MonobankException
import io.github.damienmiheev.monobank.core.RequestOutcome
import org.killbill.billing.payment.api.TransactionType
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The safety net (design A6): settles final rows Kill Bill has not heard about, refreshes open rows from monobank,
 * finds charges whose outcome is unknown in the statement, gives up expired pages, polls cards whose webhook was
 * lost, retries card removals and prunes old notifications. Every update is conditional and forward-only, so two
 * Kill Bill nodes running it at once do no harm; no lock is held across a call to monobank.
 */
@Suppress("LongParameterList")
class ReconcileJob(
    private val settings: SystemSettings,
    private val tenants: TenantSettingsProvider,
    private val clients: MonobankClients,
    private val transactions: TransactionDao,
    private val paymentMethods: PaymentMethodDao,
    private val notifications: NotificationDao,
    private val payments: PaymentService,
    private val cards: CardService,
    private val settler: TransactionSettler,
    private val metrics: PluginMetrics,
    private val now: () -> Instant,
) : AutoCloseable {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "killbill-monobank-reconcile").apply { isDaemon = true }
        }
    private val lastStatementCall = ConcurrentHashMap<UUID, Instant>()

    fun start() {
        executor.scheduleWithFixedDelay(
            ::runSafely,
            settings.reconcileIntervalSeconds,
            settings.reconcileIntervalSeconds,
            TimeUnit.SECONDS,
        )
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun runSafely() {
        try {
            runOnce()
        } catch (e: Exception) {
            // never let one failure stop the schedule
            LOG.error("Reconcile run failed; next run in {} s", settings.reconcileIntervalSeconds, e)
        }
    }

    /** One pass over everything that needs attention. Public for tests and operators. */
    fun runOnce() {
        transactions.unsettled(BATCH).filter(::settleable).forEach(::settleQuietly)
        val inFlight = Duration.ofSeconds(settings.readTimeoutSeconds).plus(SENDING_MARGIN)
        for (row in transactions.dueOpen(BATCH, now().minus(maxOf(MIN_AGE, inFlight)))) {
            try {
                reconcile(row)
            } catch (e: MonobankException) {
                LOG.info("monobank unavailable for transaction {} ({}); next run", row.transactionId, e.outcome)
            }
        }
        for (pm in paymentMethods.pendingCards(now().minus(MIN_AGE), BATCH)) {
            pollCard(pm.tenantId, pm.sourceInvoiceId!!)
        }
        cards.retryRemovals(BATCH)
        notifications.prune(now().minus(NOTIFICATION_RETENTION))
        metrics.lastReconcileRun.set(now())
    }

    private fun reconcile(row: TxRow) {
        val client = client(row.tenantId) ?: return
        when {
            row.invoiceId == null -> findUnknownCharge(row, client)

            row.expiresAt
                ?.plus(
                    EXPIRY_MARGIN,
                )?.isBefore(now()) == true && row.type in CHARGES -> giveUpPage(row, client)

            else -> refreshOrClose(row)
        }
    }

    /** A charge that may have reached monobank but has no invoice id: look for its reference in the statement. */
    private fun findUnknownCharge(
        row: TxRow,
        client: MonobankAcquiring,
    ) {
        val attempted = row.attemptedAt ?: row.createdAt
        val last = lastStatementCall[row.tenantId]
        if (last != null && last.plusSeconds(settings.statementMinIntervalSeconds).isAfter(now())) return
        lastStatementCall[row.tenantId] = now()
        val found = client.merchant.findByReference(row.reference.toString(), attempted.minus(STATEMENT_LEAD))
        val item = found.firstOrNull()
        if (item != null) {
            payments.apply(client.invoices.status(item.invoiceId), row.tenantId).toSettle.forEach(::settleQuietly)
            return
        }
        closeOrRecheck(row, attempted)
    }

    /** An unpaid page past its lifetime: remove it, then let monobank's own status decide (never the local clock). */
    private fun giveUpPage(
        row: TxRow,
        client: MonobankAcquiring,
    ) {
        try {
            client.invoices.remove(row.invoiceId!!)
        } catch (e: MonobankApiException) {
            if (e.outcome != RequestOutcome.REJECTED) throw e
            LOG.debug("monobank kept invoice {} (paid or being paid)", row.invoiceId, e)
        }
        payments.apply(client.invoices.status(row.invoiceId!!), row.tenantId).toSettle.forEach(::settleQuietly)
    }

    private fun refreshOrClose(row: TxRow) {
        val after = payments.refreshIfOpen(row)
        if (after.state.isFinal) {
            settleQuietly(after)
            return
        }
        // A refund or void that monobank has not listed yet, or a charge still processing.
        closeOrRecheck(after, after.attemptedAt ?: after.createdAt)
    }

    /** Re-checks an unresolved row at 15 min, 1 h and 6 h; after 24 h without a trace it is closed as FAILED. */
    private fun closeOrRecheck(
        row: TxRow,
        attempted: Instant,
    ) {
        val age = Duration.between(attempted, now())
        if (row.state == TxState.UNKNOWN || row.state == TxState.SENDING) {
            if (age >= GIVE_UP_AFTER) {
                metrics.unknownClosed.incrementAndGet()
                LOG.error(
                    "unknown_closed: transaction {} (reference {}) left no trace at monobank in {}; " +
                        "closed as FAILED, check it",
                    row.transactionId,
                    row.reference,
                    age,
                )
                val closed =
                    transactions.transition(
                        row.recordId,
                        setOf(TxState.UNKNOWN, TxState.SENDING),
                        TxChange(
                            TxState.FAILED,
                            errorCode = "UNKNOWN_CLOSED",
                            errorMessage = "no trace at monobank after $age",
                        ),
                    )
                settleQuietly(closed)
                return
            }
        }
        val next = RECHECKS.firstOrNull { attempted.plus(it).isAfter(now()) } ?: GIVE_UP_AFTER
        transactions.reschedule(row.recordId, maxOf(attempted.plus(next), now().plus(MIN_AGE)))
    }

    private fun pollCard(
        tenantId: UUID,
        invoiceId: String,
    ) {
        val client = client(tenantId) ?: return
        try {
            val status = client.invoices.status(invoiceId)
            payments.apply(status, tenantId)
            if (status.status == InvoiceStatus.EXPIRED || status.status == InvoiceStatus.FAILURE) {
                paymentMethods.findBySourceInvoice(invoiceId).forEach {
                    paymentMethods.setStatus(
                        it.paymentMethodId,
                        io.github.damienmiheev.killbill.monobank.db.CardStatus.FAILED,
                        setOf(io.github.damienmiheev.killbill.monobank.db.CardStatus.PENDING),
                    )
                }
            }
        } catch (e: MonobankException) {
            LOG.info("Could not poll card invoice {} ({})", invoiceId, e.outcome)
        }
    }

    private fun settleable(row: TxRow): Boolean =
        (row.attemptedAt ?: Instant.MIN)
            .plus(Duration.ofSeconds(settings.readTimeoutSeconds).plus(SENDING_MARGIN))
            .isBefore(now())

    private fun settleQuietly(row: TxRow) {
        if (!row.state.isFinal || row.kbSettledAt != null) return
        try {
            settler.settle(row)
        } catch (e: Exception) {
            // Kill Bill unavailable: next run
            LOG.warn("Could not settle transaction {}; next run", row.transactionId, e)
        }
    }

    private fun client(tenantId: UUID): MonobankAcquiring? =
        (tenants.get(tenantId) as? TenantSettings.ParseResult.Valid)?.settings?.merchantToken?.let(clients::forToken)

    private companion object {
        val LOG = LoggerFactory.getLogger(ReconcileJob::class.java)
        const val BATCH = 100
        val CHARGES = setOf(TransactionType.PURCHASE, TransactionType.AUTHORIZE)
        val MIN_AGE: Duration = Duration.ofMinutes(2)
        val SENDING_MARGIN: Duration = Duration.ofSeconds(60)
        val STATEMENT_LEAD: Duration = Duration.ofMinutes(5)
        val EXPIRY_MARGIN: Duration = Duration.ofMinutes(5)
        val RECHECKS: List<Duration> = listOf(Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6))
        val GIVE_UP_AFTER: Duration = Duration.ofHours(24)
        val NOTIFICATION_RETENTION: Duration = Duration.ofDays(400)
    }
}
