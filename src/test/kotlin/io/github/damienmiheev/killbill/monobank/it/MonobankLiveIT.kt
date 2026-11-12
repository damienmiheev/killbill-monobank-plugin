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
 * Run: `MONOBANK_TOKEN_IS_TEST=1 MONOBANK_ACQUIRING_TEST_TOKEN=... ./mvnw -Plive verify -Dit.test=MonobankLiveIT`.
 * `MONOBANK_LIVE_PAY=1` also pays pages by hand (the links are printed); `MONOBANK_LIVE_PAY=auto` lets [LivePayer]
 * pay them with `MONOBANK_TEST_CARD`, as the live workflow does.
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
        // These tests create, pay and refund invoices: never with a production token.
        check(System.getenv("MONOBANK_TOKEN_IS_TEST") == "1") {
            "set MONOBANK_TOKEN_IS_TEST=1 to confirm $TOKEN_ENV is a monobank test token"
        }
        token = fromEnv!!.trim()
        mono = MonobankAcquiring(AcquiringConfig.builder(token).cmsName("killbill-monobank-live-test").build())
        // Merchants with split payments must name a receiver on every basket item.
        val receiver =
            mono.split
                .receivers()
                .firstOrNull()
                ?.id
        stack = KillBillStack(liveToken = token)
        stack.start()
        tenant =
            stack.moneyTenant(
                listOfNotNull(
                    // webhooks cannot reach this machine; .invalid never resolves, so nothing is sent anywhere
                    "org.killbill.billing.plugin.monobank.webhookUrl=https://webhook.invalid/monobank",
                    "org.killbill.billing.plugin.monobank.destination=Kill Bill plugin live test",
                    // monobank refuses a saved-card refund without receipt items, so the tenant always sends a basket
                    "org.killbill.billing.plugin.monobank.basketItemCode=live-test",
                    receiver?.let { "org.killbill.billing.plugin.monobank.splitReceiverId=$it" },
                ).joinToString("\n"),
            )
    }

    @AfterAll
    fun stop() {
        if (::stack.isInitialized) {
            try {
                // The merchant token must never reach Kill Bill's logs.
                assertThat(stack.killbillLogs()).doesNotContain(token)
            } finally {
                stack.close()
            }
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

    private fun settledInKillBill(paymentId: String): Boolean =
        stack
            .payment(tenant, paymentId, withPluginInfo = true)
            .path("transactions")
            .first()
            .path("status")
            .asText() !=
            "PENDING"

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
        val mode = System.getenv("MONOBANK_LIVE_PAY")
        assumeTrue(mode == "1" || mode == "auto", "MONOBANK_LIVE_PAY is not 1 or auto")
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val purchase = paymentId(stack.pay(tenant, account, pm, "PURCHASE", "3.00", properties = HOSTED_SAVING))
        val hold = paymentId(stack.pay(tenant, account, pm, "AUTHORIZE", "2.00", properties = HOSTED))
        payPages(mode, purchase, hold)

        for ((id, what) in listOf(purchase to "purchase", hold to "hold")) {
            val paid = awaitPaid(id, "the $what page")
            report(what, paid.status())
            assertThat(
                paid
                    .path("transactions")
                    .first()
                    .path("status")
                    .asText(),
            ).isEqualTo("SUCCESS")
        }
        // partial capture of the hosted hold: receipt items for 1.50 (monobank refuses it without them)
        stack.followUp(tenant, hold, "CAPTURE", "1.50")
        assertThat(awaitAmount(hold, "capturedAmount", "1.50")).isEqualByComparingTo("1.50")
        // partial, then the remaining refund of the purchase
        stack.followUp(tenant, purchase, "REFUND", "1.00")
        awaitAmount(purchase, "refundedAmount", "1.00")
        stack.followUp(tenant, purchase, "REFUND", "2.00")
        assertThat(awaitAmount(purchase, "refundedAmount", "3.00")).isEqualByComparingTo("3.00")

        savedCard(account, pm)
    }

    /** The card saved by the first page: a charge refunded in full, a hold captured in full, a hold voided. */
    private fun savedCard(
        account: String,
        pm: String,
    ) {
        val card = awaitCardSettled(pm)
        report("card", card)
        // In the pipeline a card that was not saved is a failure, never a silent skip of everything below.
        if (System.getenv("MONOBANK_LIVE_PAY") == "auto") {
            assertThat(card).describedAs("card saved by the purchase page").isEqualTo("ACTIVE")
        }
        assumeTrue(card == "ACTIVE", "monobank did not save the card on this test merchant ($card)")

        val renewal = stack.pay(tenant, account, pm, "PURCHASE", "1.00")
        report("saved-card purchase", renewal.status())
        assertThat(renewal.status()).isEqualTo("SUCCESS")
        stack.followUp(tenant, paymentId(renewal), "REFUND", "1.00")
        assertThat(awaitAmount(paymentId(renewal), "refundedAmount", "1.00")).isEqualByComparingTo("1.00")

        val toCapture = stack.pay(tenant, account, pm, "AUTHORIZE", "1.00")
        assertThat(completeSavedCard(toCapture, "saved-card hold")).isEqualTo("SUCCESS")
        stack.followUp(tenant, paymentId(toCapture), "CAPTURE", "1.00")
        assertThat(awaitAmount(paymentId(toCapture), "capturedAmount", "1.00")).isEqualByComparingTo("1.00")

        val toVoid = stack.pay(tenant, account, pm, "AUTHORIZE", "1.00")
        assertThat(completeSavedCard(toVoid, "saved-card hold to void")).isEqualTo("SUCCESS")
        stack.followUp(tenant, paymentId(toVoid), "VOID")
        val voided =
            stack.awaitPayment(tenant, paymentId(toVoid), WAIT) {
                stack.payment(tenant, paymentId(toVoid), withPluginInfo = true).path("transactions").last().let { tx ->
                    tx.path("transactionType").asText() == "VOID" && tx.path("status").asText() == "SUCCESS"
                }
            }
        report("void", voided.status())
    }

    @Test
    @Order(20)
    fun `hold on a saved card`() {
        val mode = System.getenv("MONOBANK_LIVE_PAY")
        assumeTrue(mode == "1" || mode == "auto", "MONOBANK_LIVE_PAY is not 1 or auto")
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val saving = paymentId(stack.pay(tenant, account, pm, "PURCHASE", "1.00", properties = HOSTED_SAVING))
        val pageUrl = stack.payment(tenant, saving, withPluginInfo = true).prop("formUrl")!!
        if (mode == "auto") {
            val card = checkNotNull(LivePayer.TestCard.fromEnv(System.getenv("MONOBANK_TEST_CARD")))
            LivePayer(card).use { it.pay(pageUrl, "saving") { settledInKillBill(saving) } }
        } else {
            println("LIVE-PAY (purchase 1.00 UAH, saves the card): $pageUrl")
        }
        awaitPaid(saving, "the saving page")
        assertThat(awaitCardSettled(pm)).isEqualTo("ACTIVE")

        val hold = stack.pay(tenant, account, pm, "AUTHORIZE", "1.00")
        val info = stack.payment(tenant, paymentId(hold), withPluginInfo = true)
        val invoiceId = info.prop("invoiceId")
        report("hold answer", "${hold.status()} ${hold.lastTx().path("gatewayErrorCode").asText()}")
        report(
            "hold plugin info",
            "invoiceId=$invoiceId tdsUrl=${info.prop("tdsUrl")} reference=${info.prop("reference")}",
        )
        invoiceId?.let { id ->
            val status = mono.invoices.status(id)
            report(
                "hold at monobank",
                "status=${status.statusRaw} amount=${status.amount} ccy=${status.ccy} errCode=${status.errCode} " +
                    "failureReason=${status.failureReason} paymentType=${status.paymentInfo?.paymentMethodRaw}",
            )
        }
        val final = completeSavedCard(hold, "saved-card hold")
        assertThat(final).isEqualTo("SUCCESS")
        stack.followUp(tenant, paymentId(hold), "VOID")
    }

    /**
     * A saved-card charge refunded in three parts, the last one being what is left. monobank refuses a saved-card
     * refund without receipt items and checks every item against the paid basket (seen live on the SDK's merchant).
     * `-Dit.test=MonobankLiveIT#saved*refund*`.
     */
    @Test
    @Order(21)
    fun `saved-card charge refunded in parts`() {
        val mode = System.getenv("MONOBANK_LIVE_PAY")
        assumeTrue(mode == "1" || mode == "auto", "MONOBANK_LIVE_PAY is not 1 or auto")
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val saving = paymentId(stack.pay(tenant, account, pm, "PURCHASE", "1.00", properties = HOSTED_SAVING))
        val pageUrl = stack.payment(tenant, saving, withPluginInfo = true).prop("formUrl")!!
        if (mode == "auto") {
            val card = checkNotNull(LivePayer.TestCard.fromEnv(System.getenv("MONOBANK_TEST_CARD")))
            LivePayer(card).use { it.pay(pageUrl, "saving") { settledInKillBill(saving) } }
        } else {
            println("LIVE-PAY (purchase 1.00 UAH, saves the card): $pageUrl")
        }
        awaitPaid(saving, "the saving page")
        assertThat(awaitCardSettled(pm)).isEqualTo("ACTIVE")

        val charge = stack.pay(tenant, account, pm, "PURCHASE", "4.50")
        assertThat(completeSavedCard(charge, "saved-card purchase 4.50")).isEqualTo("SUCCESS")
        val id = paymentId(charge)
        for ((part, total) in listOf("1.37" to "1.37", "0.10" to "1.47", "3.03" to "4.50")) {
            stack.followUp(tenant, id, "REFUND", part)
            assertThat(awaitAmount(id, "refundedAmount", total)).isEqualByComparingTo(total)
            report(
                "refund $part",
                stack
                    .payment(tenant, id, withPluginInfo = true)
                    .lastTx()
                    .path("status")
                    .asText(),
            )
        }
        val invoiceId = stack.payment(tenant, id, withPluginInfo = true).prop("invoiceId")!!
        val status = mono.invoices.status(invoiceId)
        report("at monobank", "status=${status.status} finalAmount=${status.finalAmount}")
        assertThat(status.finalAmount).isEqualTo(0L)
    }

    private fun JsonNode.lastTx(): JsonNode = path("transactions").last()

    /**
     * A saved-card payment monobank answered `processing`: records why (a 3-D Secure link or not), confirms the test
     * 3-D Secure page in the pipeline (a person opens the printed link otherwise), and waits until monobank settles
     * it. Returns the payment's final status in Kill Bill.
     */
    private fun completeSavedCard(
        payment: JsonNode,
        what: String,
    ): String {
        val id = paymentId(payment)
        if (payment.status() != "PENDING") {
            report(what, payment.status())
            return payment.status()
        }
        val tdsUrl = stack.payment(tenant, id, withPluginInfo = true).prop("tdsUrl")
        report(what, "PENDING, 3-D Secure ${if (tdsUrl == null) "not asked" else "asked"}")
        if (tdsUrl != null) {
            if (System.getenv("MONOBANK_LIVE_PAY") == "auto") {
                val card = checkNotNull(LivePayer.TestCard.fromEnv(System.getenv("MONOBANK_TEST_CARD")))
                LivePayer(card).use { it.confirm(tdsUrl, what.replace(' ', '-')) { settledInKillBill(id) } }
            } else {
                println("LIVE-PAY 3-D Secure for $what: $tdsUrl")
            }
        }
        val settled =
            awaitPaid(id, what)
                .path("transactions")
                .first()
                .path("status")
                .asText()
        report("$what after waiting", settled)
        return settled
    }

    /** By [LivePayer] in the pipeline (`auto`), or by a person who opens the printed links (`1`). */
    private fun payPages(
        mode: String,
        purchase: String,
        hold: String,
    ) {
        val purchaseUrl = stack.payment(tenant, purchase, withPluginInfo = true).prop("formUrl")!!
        val holdUrl = stack.payment(tenant, hold, withPluginInfo = true).prop("formUrl")!!
        if (mode != "auto") {
            println("LIVE-PAY 1 (purchase 3.00 UAH, saves the card): $purchaseUrl")
            println("LIVE-PAY 2 (hold 2.00 UAH): $holdUrl")
            return
        }
        val card =
            checkNotNull(LivePayer.TestCard.fromEnv(System.getenv("MONOBANK_TEST_CARD"))) {
                "MONOBANK_TEST_CARD (number|MM/YY|CVC) is required with MONOBANK_LIVE_PAY=auto"
            }
        LivePayer(card).use { payer ->
            payer.pay(purchaseUrl, "purchase") { settledInKillBill(purchase) }
            payer.pay(holdUrl, "hold") { settledInKillBill(hold) }
        }
    }

    private fun paymentId(payment: JsonNode): String = payment.path("paymentId").asText()

    private fun report(
        what: String,
        result: String?,
    ) = println("LIVE-RESULT $what: $result")

    /** Waits until the payment's [field] (capturedAmount, refundedAmount) reaches [expected]; returns it. */
    private fun awaitAmount(
        paymentId: String,
        field: String,
        expected: String,
    ): java.math.BigDecimal {
        val payment =
            stack.awaitPayment(tenant, paymentId, WAIT) {
                stack
                    .payment(tenant, paymentId, withPluginInfo = true)
                    .path(field)
                    .decimalValue()
                    .compareTo(java.math.BigDecimal(expected)) >= 0
            }
        report("$field of $paymentId", payment.path(field).asText())
        return payment.path(field).decimalValue()
    }

    private fun awaitCardSettled(pm: String): String? {
        val deadline = System.nanoTime() + WAIT.multipliedBy(2).toNanos()
        var card = cardStatus(pm)
        while (card != "ACTIVE" && card != "FAILED" && System.nanoTime() < deadline) {
            Thread.sleep(10_000)
            card = cardStatus(pm)
        }
        return card
    }

    private companion object {
        const val TOKEN_ENV = "MONOBANK_ACQUIRING_TEST_TOKEN"
        val HOSTED = mapOf("hosted" to "true")
        val HOSTED_SAVING = mapOf("hosted" to "true", "saveCard" to "true")
        val WAIT: java.time.Duration = java.time.Duration.ofMinutes(3)
    }
}
