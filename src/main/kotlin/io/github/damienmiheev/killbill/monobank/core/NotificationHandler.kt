/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import io.github.damienmiheev.killbill.monobank.config.SystemSettings
import io.github.damienmiheev.killbill.monobank.config.TenantSettings
import io.github.damienmiheev.killbill.monobank.config.TenantSettingsProvider
import io.github.damienmiheev.killbill.monobank.db.NotificationDao
import io.github.damienmiheev.killbill.monobank.db.PaymentMethodDao
import io.github.damienmiheev.killbill.monobank.db.TransactionDao
import io.github.damienmiheev.killbill.monobank.db.TxRow
import io.github.damienmiheev.killbill.monobank.mono.MonobankClients
import io.github.damienmiheev.monobank.acquiring.invoice.InvoiceStatusResponse
import io.github.damienmiheev.monobank.acquiring.webhook.VerificationResult
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookParser
import io.github.damienmiheev.monobank.core.MonobankException
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * monobank's webhook (design A7). The webhook is the fast path; polling and the reconcile job are the safety net,
 * because monobank tries only three times and does not keep order.
 */
class NotificationHandler(
    private val settings: SystemSettings,
    private val tenants: TenantSettingsProvider,
    private val clients: MonobankClients,
    private val transactions: TransactionDao,
    private val paymentMethods: PaymentMethodDao,
    private val notifications: NotificationDao,
    private val payments: PaymentService,
    private val settler: TransactionSettler,
    private val metrics: PluginMetrics,
    private val now: () -> Instant,
) {
    enum class Result(
        val httpStatus: Int,
    ) {
        APPLIED(200),
        DUPLICATE(200),
        IGNORED(200),
        UNAUTHORIZED(401),
        RETRY_LATER(503),
    }

    fun handle(
        body: ByteArray,
        xSign: String?,
        sharedSecret: String?,
    ): Result {
        settings.notificationSharedSecret?.let { expected ->
            val given = sharedSecret?.toByteArray() ?: ByteArray(0)
            if (!MessageDigest.isEqual(given, expected.reveal().toByteArray())) return Result.UNAUTHORIZED
        }
        val status =
            try {
                WebhookParser.parse(body)
            } catch (e: MonobankException) {
                LOG.info("Ignored a webhook that is not an invoice status ({})", e.javaClass.simpleName)
                return Result.IGNORED
            }
        return try {
            process(body, xSign, status)
        } catch (e: SQLException) {
            LOG.warn(
                "Database unavailable while handling a webhook for invoice {}; monobank will retry",
                status.invoiceId,
                e,
            )
            Result.RETRY_LATER
        }
    }

    private fun process(
        body: ByteArray,
        xSign: String?,
        status: InvoiceStatusResponse,
    ): Result {
        val tenantId =
            tenantOf(status)
                ?: return Result.IGNORED.also { LOG.debug("Webhook for unknown invoice {}", status.invoiceId) }
        val tenantSettings =
            (tenants.get(tenantId) as? TenantSettings.ParseResult.Valid)?.settings ?: return Result.RETRY_LATER
        when (clients.forToken(tenantSettings.merchantToken).webhooks.check(xSign, body)) {
            VerificationResult.VALID -> {
                metrics.lastWebhookVerified.set(now())
            }

            VerificationResult.INVALID_SIGNATURE -> {
                metrics.signatureFailed.incrementAndGet()
                LOG.warn("Webhook for invoice {} failed signature verification; ignored", status.invoiceId)
                return Result.IGNORED
            }

            VerificationResult.KEY_UNAVAILABLE -> {
                LOG.warn(
                    "monobank's webhook key is unavailable; webhook for invoice {} will be retried",
                    status.invoiceId,
                )
                return Result.RETRY_LATER
            }
        }
        // Applying is idempotent and forward-only, so a redelivery is applied again rather than trusted to have
        // worked the first time; the notification is recorded only after the rows are updated.
        val applied = payments.apply(status, tenantId).toSettle
        val leftOver =
            transactions.findByInvoice(status.invoiceId).filter { row ->
                row.tenantId == tenantId && settleable(row) && applied.none { it.recordId == row.recordId }
            }
        (applied + leftOver).forEach(::settleQuietly)
        val fresh =
            notifications.record(
                sha256(body),
                tenantId,
                status.reference?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                status.invoiceId,
                status.statusRaw,
                status.modifiedDate?.toInstant(),
                "APPLIED",
            )
        return if (fresh) Result.APPLIED else Result.DUPLICATE
    }

    /** The tenant comes from the row the invoice or the opaque reference belongs to, never from the webhook itself. */
    private fun tenantOf(status: InvoiceStatusResponse): UUID? {
        transactions.findByInvoice(status.invoiceId).firstOrNull()?.let { return it.tenantId }
        val reference = status.reference?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        reference?.let(transactions::findByReference)?.let { row ->
            if (row.invoiceId == null || row.invoiceId == status.invoiceId) return row.tenantId
            LOG.warn(
                "Webhook reference {} belongs to invoice {}, not {}; ignored",
                reference,
                row.invoiceId,
                status.invoiceId,
            )
            return null
        }
        return paymentMethods.findBySourceInvoice(status.invoiceId).firstOrNull()?.tenantId
    }

    /** Final, not yet settled, and not still inside its own synchronous call. */
    private fun settleable(row: TxRow): Boolean =
        row.state.isFinal && row.kbSettledAt == null &&
            (row.attemptedAt ?: Instant.MIN).plus(inFlight()).isBefore(now())

    private fun inFlight(): Duration =
        Duration.ofSeconds(settings.readTimeoutSeconds).plusSeconds(IN_FLIGHT_MARGIN_SECONDS)

    private fun settleQuietly(row: TxRow) {
        try {
            settler.settle(row)
        } catch (e: Exception) {
            // Kill Bill is unavailable: the reconcile job settles it later
            LOG.warn("Could not settle transaction {} now; the reconcile job will", row.transactionId, e)
        }
    }

    private fun sha256(body: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(body)

    private companion object {
        val LOG = LoggerFactory.getLogger(NotificationHandler::class.java)
        const val IN_FLIGHT_MARGIN_SECONDS = 30L
    }
}
