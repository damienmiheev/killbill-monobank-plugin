/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import com.sun.net.httpserver.HttpServer
import io.github.damienmiheev.killbill.monobank.config.SystemSettings
import io.github.damienmiheev.killbill.monobank.config.TenantSettingsProvider
import io.github.damienmiheev.killbill.monobank.crypto.CardTokenCipher
import io.github.damienmiheev.killbill.monobank.db.CardStatus
import io.github.damienmiheev.killbill.monobank.db.Db
import io.github.damienmiheev.killbill.monobank.db.NotificationDao
import io.github.damienmiheev.killbill.monobank.db.PaymentMethodDao
import io.github.damienmiheev.killbill.monobank.db.TransactionDao
import io.github.damienmiheev.killbill.monobank.db.TxRow
import io.github.damienmiheev.killbill.monobank.db.TxState
import io.github.damienmiheev.killbill.monobank.mono.MonobankClients
import io.github.damienmiheev.killbill.monobank.reconcile.ReconcileJob
import io.github.damienmiheev.monobank.testkit.FakeMonobankAcquiring
import io.github.damienmiheev.monobank.testkit.Fault
import io.github.damienmiheev.monobank.testkit.MonobankPaths
import io.github.damienmiheev.monobank.testkit.WebhookDelivery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.killbill.billing.catalog.api.Currency
import org.killbill.billing.payment.api.PluginProperty
import org.killbill.billing.payment.api.TransactionType
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Holds, capture and void, refunds, review, settling, reconcile, giving up pages and health. */
class OperationsIT : ServiceHarness() {
    @Test
    fun `a refund whose answer was lost is settled from the invoice's cancel list`() {
        val (account, pm) = cardHolder()
        val payment = UUID.randomUUID()
        assertThat(
            stored(payments.charge(op(TransactionType.PURCHASE, account, payment, pm, "20.00"))).state,
        ).isEqualTo(TxState.SUCCESS)

        fake.setWebhookDelivery(WebhookDelivery.MANUAL) // the answer and the webhook are both lost
        fake.injectFault(MonobankPaths.INVOICE_CANCEL, Fault.timeout())
        val refund = stored(payments.refund(op(TransactionType.REFUND, account, payment, pm, "5.00")))
        fake.clearFaults()
        assertThat(refund.state).isEqualTo(TxState.UNKNOWN)

        val tooMuch = payments.refund(op(TransactionType.REFUND, account, payment, pm, "20.00"))
        assertThat(tooMuch).isInstanceOf(Answer.Refused::class.java)

        fake.dropPendingWebhooks { true }
        clock = clock.plus(Duration.ofMinutes(3))
        reconcile.runOnce()
        assertThat(awaitRow(refund) { it.state.isFinal }.state).isEqualTo(TxState.SUCCESS)
    }

    @Test
    fun `a hold is captured once, and every capture or void after that is refused without a call`() {
        val (account, pm, payment) = hold()

        assertThat(refusalCode(payments.capture(op(TransactionType.CAPTURE, account, payment, pm, "10.01"))))
            .isEqualTo(PaymentService.INVALID_OPERATION)
        val usd = op(TransactionType.CAPTURE, account, payment, pm, "5.00").copy(currency = Currency.USD)
        assertThat(refusalCode(payments.capture(usd))).isEqualTo(PaymentService.INVALID_OPERATION)

        val finalizeCalls = fake.requestCount(MonobankPaths.INVOICE_FINALIZE)
        val captured = stored(payments.capture(op(TransactionType.CAPTURE, account, payment, pm, "6.00")))
        assertThat(captured.state).isEqualTo(TxState.SUCCESS)
        assertThat(captured.processedAmountMinor).isEqualTo(600)

        val cancelCalls = fake.requestCount(MonobankPaths.INVOICE_CANCEL)
        assertThat(refusalCode(payments.capture(op(TransactionType.CAPTURE, account, payment, pm, "1.00"))))
            .isEqualTo(PaymentService.INVALID_OPERATION)
        assertThat(refusalCode(payments.void(op(TransactionType.VOID, account, payment, pm, null))))
            .isEqualTo(PaymentService.INVALID_OPERATION)
        assertThat(fake.requestCount(MonobankPaths.INVOICE_FINALIZE) - finalizeCalls).isEqualTo(1)
        assertThat(fake.requestCount(MonobankPaths.INVOICE_CANCEL)).isEqualTo(cancelCalls)

        // what was captured can be refunded, in its own currency only and never above the captured amount
        val usdRefund = op(TransactionType.REFUND, account, payment, pm, "1.00").copy(currency = Currency.USD)
        assertThat(refusalCode(payments.refund(usdRefund))).isEqualTo(PaymentService.INVALID_OPERATION)
        assertThat(refusalCode(payments.refund(op(TransactionType.REFUND, account, payment, pm, "6.01"))))
            .isEqualTo(PaymentService.INVALID_OPERATION)
        val refund = stored(payments.refund(op(TransactionType.REFUND, account, payment, pm, "6.00")))
        assertThat(awaitRow(refund) { it.state.isFinal }.state).isEqualTo(TxState.SUCCESS)
    }

    @Test
    fun `a capture and a void racing on one hold reach monobank once`() {
        val (account, pm, payment) = hold()
        fake.injectFault(MonobankPaths.INVOICE_FINALIZE, Fault.delay(Duration.ofMillis(800)))
        fake.injectFault(MonobankPaths.INVOICE_CANCEL, Fault.delay(Duration.ofMillis(800)))
        val before =
            fake.requestCount(MonobankPaths.INVOICE_FINALIZE) + fake.requestCount(MonobankPaths.INVOICE_CANCEL)
        val pool = Executors.newFixedThreadPool(2)
        val answers =
            pool
                .invokeAll(
                    listOf(
                        Callable { payments.capture(op(TransactionType.CAPTURE, account, payment, pm, "10.00")) },
                        Callable { payments.void(op(TransactionType.VOID, account, payment, pm, null)) },
                    ),
                ).map { it.get() }
        pool.shutdown()

        assertThat(answers.filterIsInstance<Answer.Stored>()).hasSize(1)
        assertThat(answers.filterIsInstance<Answer.Refused>()).hasSize(1)
        val after =
            fake.requestCount(MonobankPaths.INVOICE_FINALIZE) + fake.requestCount(MonobankPaths.INVOICE_CANCEL)
        assertThat(after - before).isEqualTo(1)
    }

    @Test
    fun `an unused hold is voided, counted when it nears monobank's 9 days, and not captured after them`() {
        // every hold already in the table is older than 7 days at the time checked below
        val earlier = transactions.holdsOlderThan(clock.plus(Duration.ofDays(1)))
        val (account, pm, payment) = hold()
        clock = clock.plus(Duration.ofDays(8))
        reconcile.runOnce()
        assertThat(metrics.holdsNearExpiry.get()).isEqualTo(earlier + 1)

        clock = clock.plus(Duration.ofDays(2))
        assertThat(refusalCode(payments.capture(op(TransactionType.CAPTURE, account, payment, pm, "10.00"))))
            .isEqualTo(PaymentService.INVALID_OPERATION)

        val (account2, pm2, payment2) = hold()
        val voided = stored(payments.void(op(TransactionType.VOID, account2, payment2, pm2, null)))
        assertThat(awaitRow(voided) { it.state.isFinal }.state).isEqualTo(TxState.SUCCESS)
        assertThat(fake.invoice(voided.invoiceId!!)!!.status).isEqualTo("reversed")
    }

    @Test
    fun `a webhook that arrives while the call still runs is settled by the call's own answer only`() {
        val (account, pm) = cardHolder()
        fake.injectFault(MonobankPaths.WALLET_PAYMENT, Fault.answerAfterWebhooks())
        val paid = stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "4.00")))

        assertThat(paid.state).isEqualTo(TxState.SUCCESS)
        assertThat(paid.kbSettledAt).isNotNull
        clock = clock.plus(Duration.ofMinutes(3))
        reconcile.runOnce()
        assertThat(settled.map { it.recordId }).doesNotContain(paid.recordId)
    }

    @Test
    fun `a row held for review stays out of Kill Bill until an operator fixes it there`() {
        val page = hostedPage("8.00")
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        fake.payInvoice(page.invoiceId!!)
        fake.dropPendingWebhooks { true }
        val body = fake.invoice(page.invoiceId)!!.statusJson.replace("\"amount\":800", "\"amount\":1")
        handler.handle(body.toByteArray(), fake.signer.sign(body), null)
        assertThat(transactions.findByRecord(page.recordId)!!.state).isEqualTo(TxState.REVIEW)

        clock = clock.plus(Duration.ofMinutes(3))
        reconcile.runOnce()
        val held = transactions.findByRecord(page.recordId)!!
        assertThat(held.state).isEqualTo(TxState.REVIEW)
        assertThat(held.invoiceId).isEqualTo(page.invoiceId)
        assertThat(settled.map { it.recordId }).doesNotContain(page.recordId)

        reviewed[page.recordId] = TxState.SUCCESS
        reconcile.runOnce()
        val closed = transactions.findByRecord(page.recordId)!!
        assertThat(closed.state).isEqualTo(TxState.SUCCESS)
        assertThat(closed.kbSettledAt).isNotNull
    }

    @Test
    fun `a settle that fails is tried again later, not on every run`() {
        val page = hostedPage()
        val attempts =
            java.util.concurrent.atomic
                .AtomicInteger()
        settleHook = {
            if (it.recordId == page.recordId) {
                attempts.incrementAndGet()
                throw IllegalStateException("Kill Bill is down")
            }
        }
        fake.payInvoice(page.invoiceId!!)
        awaitRow(page) { it.state == TxState.SUCCESS }
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (attempts.get() == 0) {
            check(System.nanoTime() < deadline) { "the webhook never tried to settle" }
            Thread.sleep(50)
        }
        assertThat(attempts.get()).isEqualTo(1) // the webhook tried

        clock = clock.plus(Duration.ofMinutes(3))
        reconcile.runOnce()
        assertThat(attempts.get()).isEqualTo(2) // the job tried once, then backs off

        clock = clock.plus(Duration.ofSeconds(30))
        reconcile.runOnce()
        assertThat(attempts.get()).isEqualTo(2)

        settleHook = {}
        clock = clock.plus(Duration.ofMinutes(10))
        reconcile.runOnce()
        assertThat(transactions.findByRecord(page.recordId)!!.kbSettledAt).isNotNull
    }

    @Test
    fun `two nodes reconciling at once give up an expired page once`() {
        val page = hostedPage()
        clock = clock.plus(Duration.ofHours(2))
        val other = newReconcileJob()
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.invokeAll(listOf(Callable { reconcile.runOnce() }, Callable { other.runOnce() })).forEach { it.get() }
        } finally {
            pool.shutdown()
            other.close()
        }
        val closed = transactions.findByRecord(page.recordId)!!
        assertThat(closed.state).isEqualTo(TxState.FAILED)
        assertThat(closed.kbSettledAt).isNotNull
        assertThat(fake.invoice(page.invoiceId!!)!!.status).isEqualTo("expired")
    }

    @Test
    fun `an unpaid page is given up, a paid one is not, and another tenant's page is not found`() {
        val unpaid = hostedPage()
        val done = payments.cancelHosted(unpaid.paymentId, tenantId)
        assertThat(done).isInstanceOf(PaymentService.Cancellation.Done::class.java)
        assertThat((done as PaymentService.Cancellation.Done).row.state).isEqualTo(TxState.FAILED)

        val paid = hostedPage()
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        fake.payInvoice(paid.invoiceId!!)
        assertThat(payments.cancelHosted(paid.paymentId, tenantId))
            .isInstanceOf(PaymentService.Cancellation.AlreadyPaid::class.java)
        assertThat(transactions.findByRecord(paid.recordId)!!.state).isEqualTo(TxState.SUCCESS)

        assertThat(payments.cancelHosted(paid.paymentId, UUID.randomUUID()))
            .isEqualTo(PaymentService.Cancellation.NotFound)
        assertThat(payments.cancelHosted(UUID.randomUUID(), tenantId)).isEqualTo(PaymentService.Cancellation.NotFound)
    }

    @Test
    fun `a page monobank no longer finds after removing it is given up as cancelled by the merchant`() {
        val page = hostedPage()
        fake.injectFault(
            MonobankPaths.INVOICE_STATUS,
            Fault.response(404, """{"errCode":"NOT_FOUND","errText":"invoice not found"}"""),
        )
        val done = payments.cancelHosted(page.paymentId, tenantId)

        assertThat(done).isInstanceOf(PaymentService.Cancellation.Done::class.java)
        val row = (done as PaymentService.Cancellation.Done).row
        assertThat(row.state).isEqualTo(TxState.FAILED)
        assertThat(row.errorCode).isEqualTo(PaymentService.CANCELED_BY_MERCHANT)
    }

    @Test
    fun `a review fixed as failed in Kill Bill closes the row as failed`() {
        val page = hostedPage("8.00")
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        fake.payInvoice(page.invoiceId!!)
        fake.dropPendingWebhooks { true }
        val body = fake.invoice(page.invoiceId)!!.statusJson.replace("\"amount\":800", "\"amount\":2")
        handler.handle(body.toByteArray(), fake.signer.sign(body), null)
        assertThat(transactions.findByRecord(page.recordId)!!.state).isEqualTo(TxState.REVIEW)

        reviewed[page.recordId] = TxState.FAILED
        reconcile.runOnce()

        assertThat(transactions.findByRecord(page.recordId)!!.state).isEqualTo(TxState.FAILED)
    }

    @Test
    fun `a refund monobank never finishes is held for review after a day`() {
        val (account, pm) = cardHolder()
        val payment = UUID.randomUUID()
        stored(payments.charge(op(TransactionType.PURCHASE, account, payment, pm, "30.00")))
        fake.setRefundSettlement(io.github.damienmiheev.monobank.testkit.RefundSettlement.MANUAL)
        try {
            val refund = stored(payments.refund(op(TransactionType.REFUND, account, payment, pm, "10.00")))
            assertThat(refund.state).isEqualTo(TxState.PENDING)
            val blocked = payments.refund(op(TransactionType.REFUND, account, payment, pm, "5.00"))
            assertThat(refusalCode(blocked)).isEqualTo(PaymentService.PREVIOUS_OPERATION_UNRESOLVED)

            val before = metrics.operationStale.get()
            clock = clock.plus(Duration.ofHours(25))
            reconcile.runOnce()

            assertThat(transactions.findByRecord(refund.recordId)!!.state).isEqualTo(TxState.REVIEW)
            assertThat(metrics.operationStale.get()).isEqualTo(before + 1)
        } finally {
            fake.setRefundSettlement(io.github.damienmiheev.monobank.testkit.RefundSettlement.AUTOMATIC)
        }
    }

    @Test
    fun `the plugin is unhealthy only when the reconcile job has stopped running`() {
        val health =
            io.github.damienmiheev.killbill.monobank.health.MonobankHealthcheck(
                dataSource,
                TenantSettingsProvider(readRaw = { null }, boundAlias = { null }, env = { null }),
                metrics,
                Duration.ofSeconds(30),
            ) { clock }
        reconcile.runOnce()
        assertThat(health.getHealthStatus(null, null).isHealthy).isTrue()

        clock = clock.plus(Duration.ofSeconds(149)) // three intervals and a minute
        assertThat(health.getHealthStatus(null, null).isHealthy).isTrue()
        clock = clock.plus(Duration.ofSeconds(2))
        val stalled = health.getHealthStatus(null, null)
        assertThat(stalled.isHealthy).isFalse()
        assertThat(stalled.details["reconcile"]).isEqualTo("stalled")

        reconcile.runOnce()
        assertThat(health.getHealthStatus(null, null).isHealthy).isTrue()
    }
}
