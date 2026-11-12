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

/** Charges, idempotency, unknown outcomes and webhooks against the fake monobank and a real PostgreSQL. */
class PaymentServiceIT : ServiceHarness() {
    @Test
    fun `a request that never left is CANCELED and a retry with a new transaction succeeds`() {
        val (account, pm) = cardHolder()
        fake.pause()
        val refused = stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "5.00")))
        fake.resume()

        assertThat(refused.state).isEqualTo(TxState.CANCELED)
        assertThat(refused.errorCode).isEqualTo(PaymentService.NOT_SENT)
        assertThat(stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "5.00"))).state)
            .isEqualTo(TxState.SUCCESS)
    }

    @Test
    fun `the same Kill Bill transaction never reaches monobank twice`() {
        val (account, pm) = cardHolder()
        val first = op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "7.00")
        val before = fake.requestCount(MonobankPaths.WALLET_PAYMENT)

        val a = stored(payments.charge(first))
        val b = stored(payments.charge(first))

        assertThat(b.recordId).isEqualTo(a.recordId)
        assertThat(fake.requestCount(MonobankPaths.WALLET_PAYMENT) - before).isEqualTo(1)
    }

    @Test
    fun `an unknown charge with no invoice id is found in the statement`() {
        val (account, pm) = cardHolder()
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        fake.injectFault(MonobankPaths.WALLET_PAYMENT, Fault.timeout())
        val unknown = stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "9.00")))
        fake.clearFaults()
        assertThat(unknown.state).isEqualTo(TxState.UNKNOWN)
        assertThat(unknown.invoiceId).isNull()

        clock = clock.plus(Duration.ofMinutes(3))
        reconcile.runOnce()

        val found = transactions.findByRecord(unknown.recordId)!!
        assertThat(found.state).isEqualTo(TxState.SUCCESS)
        assertThat(found.invoiceId).isNotNull
        assertThat(settled.map { it.recordId }).contains(unknown.recordId)
    }

    @Test
    fun `an unknown charge that left no trace stays UNKNOWN, blocks the account, and closes after 24 hours`() {
        val (account, pm) = cardHolder()
        fake.injectFault(MonobankPaths.WALLET_PAYMENT, Fault.timeoutWithoutProcessing())
        val unknown = stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "11.00")))
        fake.clearFaults()
        assertThat(unknown.state).isEqualTo(TxState.UNKNOWN)

        val blocked = payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "11.00"))
        assertThat((blocked as Answer.Refused).code).isEqualTo(PaymentService.PREVIOUS_CHARGE_UNRESOLVED)

        clock = clock.plus(Duration.ofHours(1))
        reconcile.runOnce()
        assertThat(transactions.findByRecord(unknown.recordId)!!.state).isEqualTo(TxState.UNKNOWN)

        clock = clock.plus(Duration.ofHours(24))
        Thread.sleep(1100) // the statement is read at most once a second per tenant in this test
        reconcile.runOnce()
        assertThat(transactions.findByRecord(unknown.recordId)!!.state).isEqualTo(TxState.FAILED)
        assertThat(metrics.unknownClosed.get()).isGreaterThan(0)
    }

    @Test
    fun `two concurrent charges on one account reach monobank once`() {
        val (account, pm) = cardHolder()
        fake.injectFault(MonobankPaths.WALLET_PAYMENT, Fault.delay(Duration.ofMillis(800)))
        val before = fake.requestCount(MonobankPaths.WALLET_PAYMENT)
        val pool = Executors.newFixedThreadPool(2)
        val answers =
            pool
                .invokeAll(
                    List(2) {
                        Callable {
                            payments.charge(
                                op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "3.00"),
                            )
                        }
                    },
                ).map { it.get() }
        pool.shutdown()

        assertThat(
            answers.count { it is Answer.Refused && it.code == PaymentService.PREVIOUS_CHARGE_UNRESOLVED },
        ).isEqualTo(1)
        assertThat(fake.requestCount(MonobankPaths.WALLET_PAYMENT) - before).isEqualTo(1)
    }

    @Test
    fun `a lost card webhook is recovered by polling the invoice that saved it`() {
        val account = UUID.randomUUID()
        val pm = UUID.randomUUID()
        paymentMethods.add(tenantId, account, pm)
        val hosted =
            stored(
                payments.charge(
                    op(
                        TransactionType.PURCHASE,
                        account,
                        UUID.randomUUID(),
                        pm,
                        "1.00",
                        "hosted" to "true",
                        "saveCard" to "true",
                    ),
                ),
            )
        fake.dropNextWebhooks(10) { it.invoiceId == hosted.invoiceId }
        fake.payInvoice(hosted.invoiceId!!)
        Thread.sleep(500)
        assertThat(paymentMethods.find(pm)!!.cardStatus).isEqualTo(CardStatus.PENDING)

        clock = clock.plus(Duration.ofMinutes(3))
        reconcile.runOnce()

        assertThat(paymentMethods.find(pm)!!.cardStatus).isEqualTo(CardStatus.ACTIVE)
        assertThat(transactions.findByRecord(hosted.recordId)!!.state).isEqualTo(TxState.SUCCESS)
    }

    @Test
    fun `an expired page fails only on monobank's word, and a page paid at the last moment stays paid`() {
        val account = UUID.randomUUID()
        val unpaid =
            stored(
                payments.charge(
                    op(
                        TransactionType.PURCHASE,
                        account,
                        UUID.randomUUID(),
                        null,
                        "4.00",
                        "hosted" to "true",
                    ),
                ),
            )
        val paid =
            stored(
                payments.charge(
                    op(
                        TransactionType.PURCHASE,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "4.00",
                        "hosted" to "true",
                    ),
                ),
            )
        fake.dropNextWebhooks(10) { it.invoiceId == paid.invoiceId }
        fake.payInvoice(paid.invoiceId!!)

        clock = clock.plus(Duration.ofHours(2))
        reconcile.runOnce()

        assertThat(transactions.findByRecord(unpaid.recordId)!!.state).isEqualTo(TxState.FAILED)
        assertThat(fake.invoice(unpaid.invoiceId!!)!!.status).isEqualTo("expired")
        assertThat(transactions.findByRecord(paid.recordId)!!.state).isEqualTo(TxState.SUCCESS)
    }

    @Test
    fun `duplicate and reordered webhooks end in the newest state`() {
        val account = UUID.randomUUID()
        val hosted =
            stored(
                payments.charge(
                    op(
                        TransactionType.PURCHASE,
                        account,
                        UUID.randomUUID(),
                        null,
                        "6.00",
                        "hosted" to "true",
                    ),
                ),
            )
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        fake.payInvoice(hosted.invoiceId!!)

        val delivered = fake.deliverWebhooksInReverseOrder()
        delivered.forEach { fake.redeliver(it) }

        assertThat(transactions.findByRecord(hosted.recordId)!!.state).isEqualTo(TxState.SUCCESS)
        assertThat(settled.count { it.recordId == hosted.recordId }).isEqualTo(1)
    }

    @Test
    fun `an amount that does not match goes to REVIEW`() {
        val hosted =
            stored(
                payments.charge(
                    op(
                        TransactionType.PURCHASE,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "8.00",
                        "hosted" to "true",
                    ),
                ),
            )
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        fake.payInvoice(hosted.invoiceId!!)
        fake.dropPendingWebhooks { true }
        val body = fake.invoice(hosted.invoiceId)!!.statusJson.replace("\"amount\":800", "\"amount\":1")
        val result = handler.handle(body.toByteArray(), fake.signer.sign(body), null)

        assertThat(result).isEqualTo(NotificationHandler.Result.APPLIED)
        assertThat(transactions.findByRecord(hosted.recordId)!!.state).isEqualTo(TxState.REVIEW)
        assertThat(metrics.amountMismatch.get()).isGreaterThan(0)
    }

    @Test
    fun `a forged signature is ignored and a missing key asks monobank to retry`() {
        val hosted =
            stored(
                payments.charge(
                    op(
                        TransactionType.PURCHASE,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "2.00",
                        "hosted" to "true",
                    ),
                ),
            )
        val body = fake.invoice(hosted.invoiceId!!)!!.statusJson.replace("\"created\"", "\"success\"")

        assertThat(
            handler.handle(body.toByteArray(), "bm90IGEgc2lnbmF0dXJl", null),
        ).isEqualTo(NotificationHandler.Result.UNAUTHORIZED)
        assertThat(transactions.findByRecord(hosted.recordId)!!.state).isEqualTo(TxState.PENDING)
    }

    @Test
    fun `a card monobank no longer knows is retired and declines without another call`() {
        val (account, pm) = cardHolder()
        val token = fake.invoices().first { it.cardToken != null && it.walletId == account.toString() }.cardToken!!
        io.github.damienmiheev.monobank.acquiring
            .MonobankAcquiring(
                io.github.damienmiheev.monobank.acquiring.AcquiringConfig
                    .builder(fake.token)
                    .options(fake.clientOptions())
                    .build(),
            ).wallet
            .deleteCard(token)

        val declined = stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "1.00")))

        assertThat(declined.state).isEqualTo(TxState.FAILED)
        assertThat(declined.errorCode).isEqualTo("TOKEN_NOT_FOUND")
        assertThat(paymentMethods.find(pm)!!.cardStatus).isEqualTo(CardStatus.FAILED)
        val next = payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "1.00"))
        assertThat((next as Answer.Refused).code).isEqualTo(PaymentService.NO_ACTIVE_CARD)
    }

    @Test
    fun `a basket that does not add up is refused before anything is stored`() {
        val answer =
            payments.charge(
                op(
                    TransactionType.PURCHASE,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    "5.00",
                    "hosted" to "true",
                    "basketOrder" to """[{"name":"Audit","qty":1,"sum":100,"code":"audit"}]""",
                ),
            )
        assertThat((answer as Answer.Refused).code).isEqualTo(PaymentService.INVALID_BASKET)
    }

    @Test
    fun `a webhook for an unknown reference, or one that points at another invoice, changes nothing`() {
        val page = hostedPage()
        val json = fake.invoice(page.invoiceId!!)!!.statusJson.replace("\"created\"", "\"success\"")

        val stranger =
            json
                .replace(page.invoiceId, "unknown-invoice-1")
                .replace(page.reference.toString(), UUID.randomUUID().toString())
        assertThat(handler.handle(stranger.toByteArray(), fake.signer.sign(stranger), null))
            .isEqualTo(NotificationHandler.Result.IGNORED)

        val misdirected = json.replace(page.invoiceId, "unknown-invoice-2")
        assertThat(handler.handle(misdirected.toByteArray(), fake.signer.sign(misdirected), null))
            .isEqualTo(NotificationHandler.Result.IGNORED)

        assertThat(transactions.findByRecord(page.recordId)!!.state).isEqualTo(TxState.PENDING)
    }

    @Test
    fun `a refund made outside the plugin is reported as an unexpected reversal`() {
        val (account, pm) = cardHolder()
        val charge = stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "12.00")))
        val before = metrics.unexpectedReversal.get()

        fake.refundExternally(charge.invoiceId!!, 500)
        fake.awaitWebhooks(charge.invoiceId, 1, Duration.ofSeconds(5))

        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (metrics.unexpectedReversal.get() == before) {
            check(System.nanoTime() < deadline) { "the reversal was never reported" }
            Thread.sleep(100)
        }
        assertThat(transactions.findByRecord(charge.recordId)!!.state).isEqualTo(TxState.SUCCESS)
    }

    @Test
    fun `a success reported after the charge was closed as failed is a conflict, never a silent change`() {
        val page = hostedPage()
        fake.expireInvoice(page.invoiceId!!)
        assertThat(payments.refreshIfOpen(page).state).isEqualTo(TxState.FAILED)
        val before = metrics.stateConflict.get()

        val late = fake.invoice(page.invoiceId)!!.statusJson.replace("\"expired\"", "\"success\"")
        handler.handle(late.toByteArray(), fake.signer.sign(late), null)

        assertThat(metrics.stateConflict.get()).isGreaterThan(before)
        assertThat(transactions.findByRecord(page.recordId)!!.state).isEqualTo(TxState.FAILED)
    }

    @Test
    fun `after monobank rotates its key, webhooks verify with the new one, and an unreachable key asks for a retry`() {
        try {
            val page = hostedPage()
            fake.rotateKey()
            handler = newHandler() // a node that has not fetched the key yet, as after a restart
            val paid = fake.invoice(page.invoiceId!!)!!.statusJson.replace("\"created\"", "\"success\"")
            assertThat(handler.handle(paid.toByteArray(), fake.signer.sign(paid), null))
                .isEqualTo(NotificationHandler.Result.APPLIED)

            val other = hostedPage()
            fake.rotateKey()
            handler = newHandler()
            val body = fake.invoice(other.invoiceId!!)!!.statusJson.replace("\"created\"", "\"success\"")
            val signature = fake.signer.sign(body)
            fake.pause()
            try {
                assertThat(handler.handle(body.toByteArray(), signature, null))
                    .isEqualTo(NotificationHandler.Result.RETRY_LATER)
            } finally {
                fake.resume()
            }
            assertThat(transactions.findByRecord(other.recordId)!!.state).isEqualTo(TxState.PENDING)
        } finally {
            handler = newHandler() // the rest of the class verifies with the current key
        }
    }

    @Test
    fun `a saved card asked for 3-D Secure stays pending with its link, then fails when it expires`() {
        val (account, pm) = cardHolder()
        fake.enqueueWalletPaymentOutcome(
            io.github.damienmiheev.monobank.testkit.WalletPaymentOutcome
                .threeDs(),
        )
        val pending =
            stored(
                payments.charge(
                    op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "2.50", "initiationKind" to "client"),
                ),
            )
        assertThat(pending.state).isEqualTo(TxState.PENDING)
        assertThat(pending.tdsUrl).isNotBlank

        fake.expireInvoice(pending.invoiceId!!)
        val failed = payments.refreshIfOpen(pending)
        assertThat(failed.state).isEqualTo(TxState.FAILED)
        assertThat(failed.errorCode).isEqualTo(PaymentService.EXPIRED)
    }

    @Test
    fun `a declined saved-card charge fails with monobank's code`() {
        val (account, pm) = cardHolder()
        fake.enqueueWalletPaymentOutcome(
            io.github.damienmiheev.monobank.testkit.WalletPaymentOutcome
                .declined("59", "insufficient funds"),
        )
        val declined = stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "2.00")))

        assertThat(declined.state).isEqualTo(TxState.FAILED)
        assertThat(declined.errorCode).isEqualTo("59")
        assertThat(paymentMethods.find(pm)!!.cardStatus).isEqualTo(CardStatus.ACTIVE)
    }

    @Test
    fun `a euro page paid in another currency is held for review, never settled as paid`() {
        val eur =
            op(TransactionType.PURCHASE, UUID.randomUUID(), UUID.randomUUID(), null, "15.50", "hosted" to "true")
                .copy(currency = Currency.EUR)
        val page = stored(payments.charge(eur))
        val invoice = fake.invoice(page.invoiceId!!)!!
        assertThat(invoice.ccy).isEqualTo(978)
        assertThat(invoice.amount).isEqualTo(1550L)
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        fake.payInvoice(page.invoiceId)
        fake.dropPendingWebhooks { true }

        val inHryvnia =
            fake
                .invoice(page.invoiceId)!!
                .statusJson
                .replace("\"ccy\":978", "\"ccy\":980")
        assertThat(inHryvnia).contains("\"ccy\":980")
        handler.handle(inHryvnia.toByteArray(), fake.signer.sign(inHryvnia), null)

        val held = transactions.findByRecord(page.recordId)!!
        assertThat(held.state).isEqualTo(TxState.REVIEW)
        assertThat(settled.map { it.recordId }).doesNotContain(page.recordId)
    }
}
