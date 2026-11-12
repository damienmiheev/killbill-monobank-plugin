/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.it

import com.fasterxml.jackson.databind.JsonNode
import io.github.damienmiheev.monobank.acquiring.AcquiringConfig
import io.github.damienmiheev.monobank.acquiring.MonobankAcquiring
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * The plugin in a real Kill Bill against monobank's **real** acquiring API, with a test merchant token. Skipped
 * unless `MONOBANK_ACQUIRING_TEST_TOKEN` is set, so it never runs on pull requests (fork PRs cannot reach the
 * secret). Never use a production token: these tests create and give up small invoices.
 *
 * Run: `MONOBANK_ACQUIRING_TEST_TOKEN=... ./mvnw verify -Dit.test=MonobankLiveIT`
 *
 * Webhooks cannot reach a Kill Bill on this machine, so everything settles the way a lost webhook does: Kill Bill
 * reads the payment with plugin info and the plugin asks monobank.
 */
@Tag("live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MonobankLiveIT {
    private lateinit var token: String
    private lateinit var mono: MonobankAcquiring
    private lateinit var stack: KillBillStack
    private lateinit var tenant: KillBillStack.TestTenant

    @BeforeAll
    fun start() {
        val fromEnv = System.getenv(TOKEN_ENV)
        assumeTrue(!fromEnv.isNullOrBlank(), "$TOKEN_ENV is not set")
        token = fromEnv!!.trim()
        mono = MonobankAcquiring(AcquiringConfig.builder(token).cmsName("killbill-monobank-live-test").build())
        // Merchants with split payments must name a receiver on every basket item.
        val receiver =
            runCatching {
                mono.split
                    .receivers()
                    .firstOrNull()
                    ?.id
            }.getOrNull()
        stack = KillBillStack(liveToken = token)
        stack.start()
        tenant =
            stack.moneyTenant(
                listOfNotNull(
                    // webhooks cannot reach this machine; .invalid never resolves, so nothing is sent anywhere
                    "org.killbill.billing.plugin.monobank.webhookUrl=https://webhook.invalid/monobank",
                    "org.killbill.billing.plugin.monobank.destination=Kill Bill plugin live test",
                    receiver?.let { "org.killbill.billing.plugin.monobank.splitReceiverId=$it" },
                ).joinToString("\n"),
            )
    }

    @AfterAll
    fun stop() {
        if (::stack.isInitialized) {
            // The merchant token must never reach Kill Bill's logs.
            assertThat(stack.killbillLogs()).doesNotContain(token)
            stack.close()
        }
    }

    private fun JsonNode.status(): String = path("transactions").last().path("status").asText()

    private fun JsonNode.prop(key: String): String? =
        path("transactions")
            .last()
            .path("properties")
            .firstOrNull { it.path("key").asText() == key }
            ?.path("value")
            ?.asText()

    private fun hostedPage(
        type: String,
        amount: String = "1.00",
    ): Pair<String, String> {
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val pending = stack.pay(tenant, account, pm, type, amount, properties = mapOf("hosted" to "true"))
        val paymentId = pending.path("paymentId").asText()
        val withInfo = stack.payment(tenant, paymentId, withPluginInfo = true)
        assertThat(withInfo.status()).describedAs(withInfo.toString()).isEqualTo("PENDING")
        return paymentId to withInfo.prop("invoiceId")!!
    }

    @Test
    @Order(1)
    fun `the webhook key the plugin serves is monobank's`() {
        val served = stack.get("/plugins/killbill-monobank/pubkey", tenant)
        assertThat(served.status).isEqualTo(200)
        assertThat(served.json().path("key").asText()).isEqualTo(mono.merchant.publicKey().key)
    }

    @Test
    @Order(2)
    fun `a hosted purchase creates a real invoice, and giving it up removes it at monobank`() {
        val (paymentId, invoiceId) = hostedPage("PURCHASE")
        val invoice = mono.invoices.status(invoiceId)
        assertThat(invoice.statusRaw).isEqualTo("created")
        assertThat(invoice.amount).isEqualTo(100L)
        val formUrl = stack.payment(tenant, paymentId, withPluginInfo = true).prop("formUrl")
        assertThat(formUrl).startsWith("https://")

        val cancelled =
            stack.post(
                "/plugins/killbill-monobank/payments/$paymentId/cancel",
                "",
                "application/json",
                tenant,
            )
        assertThat(cancelled.status).describedAs(cancelled.body).isEqualTo(200)
        assertThat(stack.awaitPayment(tenant, paymentId) { it.status() == "PAYMENT_FAILURE" }.status())
            .isEqualTo("PAYMENT_FAILURE")
        assertThat(runCatching { mono.invoices.status(invoiceId).statusRaw }.getOrNull()).isNotEqualTo("created")
    }

    @Test
    @Order(3)
    fun `a hosted authorize asks monobank for a hold`() {
        val (paymentId, invoiceId) = hostedPage("AUTHORIZE", "2.00")
        assertThat(mono.invoices.status(invoiceId).statusRaw).isEqualTo("created")
        val cancelled =
            stack.post(
                "/plugins/killbill-monobank/payments/$paymentId/cancel",
                "",
                "application/json",
                tenant,
            )
        assertThat(cancelled.status).describedAs(cancelled.body).isEqualTo(200)
    }

    @Test
    @Order(4)
    fun `reading a pending page with plugin info asks monobank and keeps it pending`() {
        val (paymentId, invoiceId) = hostedPage("PURCHASE")
        repeat(2) { assertThat(stack.payment(tenant, paymentId, withPluginInfo = true).status()).isEqualTo("PENDING") }
        assertThat(mono.invoices.status(invoiceId).statusRaw).isEqualTo("created")
        stack.post("/plugins/killbill-monobank/payments/$paymentId/cancel", "", "application/json", tenant)
    }

    private fun cardStatus(pm: String): String? =
        stack
            .get("/1.0/kb/paymentMethods/$pm?withPluginInfo=true", tenant)
            .json()
            .path("pluginInfo")
            .path("properties")
            .firstOrNull { it.path("key").asText() == "cardStatus" }
            ?.path("value")
            ?.asText()

    private fun awaitPaid(
        paymentId: String,
        what: String,
    ): JsonNode {
        val deadline =
            System.nanoTime() +
                java.time.Duration
                    .ofMinutes(15)
                    .toNanos()
        while (true) {
            val payment = stack.payment(tenant, paymentId, withPluginInfo = true)
            if (payment
                    .path("transactions")
                    .first()
                    .path("status")
                    .asText() != "PENDING"
            ) {
                return payment
            }
            check(System.nanoTime() < deadline) { "$what was not paid within 15 minutes" }
            Thread.sleep(5_000)
        }
    }

    /**
     * Pays real test pages: the person running it opens the two printed links and pays each with a test card
     * (with a test token monobank accepts any Luhn-valid number, any future expiry and any CVV). Runs only with
     * `MONOBANK_LIVE_PAY=1`.
     */
    @Test
    @Order(10)
    fun `paid pages settle, the saved card pays again, holds are captured and voided, and refunds go through`() {
        assumeTrue(System.getenv("MONOBANK_LIVE_PAY") == "1", "MONOBANK_LIVE_PAY is not 1")
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val purchase =
            stack
                .pay(
                    tenant,
                    account,
                    pm,
                    "PURCHASE",
                    "3.00",
                    properties =
                        mapOf(
                            "hosted" to "true",
                            "saveCard" to "true",
                        ),
                ).path("paymentId")
                .asText()
        val hold =
            stack
                .pay(tenant, account, pm, "AUTHORIZE", "2.00", properties = mapOf("hosted" to "true"))
                .path("paymentId")
                .asText()
        val purchaseUrl = stack.payment(tenant, purchase, withPluginInfo = true).prop("formUrl")
        val holdUrl = stack.payment(tenant, hold, withPluginInfo = true).prop("formUrl")
        println("LIVE-PAY 1 (purchase 3.00 UAH, saves the card): $purchaseUrl")
        println("LIVE-PAY 2 (hold 2.00 UAH): $holdUrl")

        val paid = awaitPaid(purchase, "the purchase page")
        println("LIVE-RESULT purchase: ${paid.status()}")
        assertThat(
            paid
                .path("transactions")
                .first()
                .path("status")
                .asText(),
        ).isEqualTo("SUCCESS")
        val held = awaitPaid(hold, "the hold page")
        println("LIVE-RESULT hold: ${held.status()}")
        assertThat(
            held
                .path("transactions")
                .first()
                .path("status")
                .asText(),
        ).isEqualTo("SUCCESS")

        // partial capture of the hosted hold
        stack.followUp(tenant, hold, "CAPTURE", "1.50")
        val captured =
            stack.awaitPayment(tenant, hold, java.time.Duration.ofMinutes(2)) {
                stack
                    .payment(tenant, hold, withPluginInfo = true)
                    .path("capturedAmount")
                    .decimalValue()
                    .signum() > 0
            }
        println("LIVE-RESULT capture: ${captured.status()} captured=${captured.path("capturedAmount")}")
        assertThat(captured.path("capturedAmount").decimalValue()).isEqualByComparingTo("1.50")

        // partial, then full refund of the purchase
        stack.followUp(tenant, purchase, "REFUND", "1.00")
        stack.awaitPayment(tenant, purchase, java.time.Duration.ofMinutes(3)) {
            stack
                .payment(tenant, purchase, withPluginInfo = true)
                .path("refundedAmount")
                .decimalValue()
                .signum() > 0
        }
        stack.followUp(tenant, purchase, "REFUND", "2.00")
        val refunded =
            stack.awaitPayment(tenant, purchase, java.time.Duration.ofMinutes(3)) {
                stack
                    .payment(tenant, purchase, withPluginInfo = true)
                    .path("refundedAmount")
                    .decimalValue()
                    .compareTo(java.math.BigDecimal("3.00")) == 0
            }
        println("LIVE-RESULT refunds: refunded=${refunded.path("refundedAmount")}")

        // the saved card, if monobank saved it for this test merchant
        val deadline =
            System.nanoTime() +
                java.time.Duration
                    .ofMinutes(4)
                    .toNanos()
        var card = cardStatus(pm)
        while (card != "ACTIVE" && card != "FAILED" && System.nanoTime() < deadline) {
            Thread.sleep(10_000)
            card = cardStatus(pm)
        }
        println("LIVE-RESULT card: $card")
        assumeTrue(card == "ACTIVE", "monobank did not save the card on this test merchant ($card)")
        val renewal = stack.pay(tenant, account, pm, "PURCHASE", "1.00")
        println("LIVE-RESULT saved-card purchase: ${renewal.status()} ${renewal.prop("invoiceId") ?: ""}")
        assertThat(renewal.status()).isEqualTo("SUCCESS")
        val cardHold = stack.pay(tenant, account, pm, "AUTHORIZE", "1.00")
        println("LIVE-RESULT saved-card hold: ${cardHold.status()}")
        assertThat(cardHold.status()).isEqualTo("SUCCESS")
        val holdId = cardHold.path("paymentId").asText()
        stack.followUp(tenant, holdId, "VOID")
        val voided =
            stack.awaitPayment(tenant, holdId, java.time.Duration.ofMinutes(2)) {
                stack.payment(tenant, holdId, withPluginInfo = true).path("transactions").last().let { tx ->
                    tx.path("transactionType").asText() == "VOID" && tx.path("status").asText() == "SUCCESS"
                }
            }
        println("LIVE-RESULT void: ${voided.status()}")
    }

    private companion object {
        const val TOKEN_ENV = "MONOBANK_ACQUIRING_TEST_TOKEN"
    }
}
