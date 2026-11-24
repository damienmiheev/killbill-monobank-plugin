/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.it

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.damienmiheev.monobank.testkit.FakeMonobankAcquiring
import io.github.damienmiheev.monobank.testkit.Fault
import io.github.damienmiheev.monobank.testkit.MonobankPaths
import io.github.damienmiheev.monobank.testkit.RefundSettlement
import io.github.damienmiheev.monobank.testkit.SplitReceiver
import io.github.damienmiheev.monobank.testkit.WebhookDelivery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/**
 * Money moving through a real Kill Bill 0.24.22 with the bundle installed, against the SDK's fake monobank, driven
 * through Kill Bill's REST API exactly as a billing application would.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentFlowIT {
    private lateinit var fake: FakeMonobankAcquiring
    private lateinit var stack: KillBillStack
    private val json = ObjectMapper()

    @BeforeAll
    fun start() {
        fake =
            FakeMonobankAcquiring
                .builder()
                // like monobank's test merchant: receipts and split payments on
                .fiscalization(true)
                .splitPayments(true)
                .build()
                .start()
        stack = KillBillStack(fake = fake)
        stack.start()
        stack.moneyTenant("org.killbill.billing.plugin.monobank.splitReceiverId=${SplitReceiver.DEFAULT.id}")
    }

    @AfterAll
    fun stop() {
        if (::stack.isInitialized) stack.close()
        if (::fake.isInitialized) fake.close()
    }

    /** One test's faults or held webhooks never reach the next. */
    @AfterEach
    fun resetFake() {
        fake.clearFaults()
        fake.resume()
        fake.setWebhookDelivery(WebhookDelivery.AUTOMATIC)
        fake.dropPendingWebhooks { true }
    }

    private fun counters(): JsonNode =
        stack
            .get("/plugins/killbill-monobank/healthcheck", stack.moneyTenant())
            .json()
            .path("details")
            .path("counters")

    private fun JsonNode.tx(index: Int = 0): JsonNode = path("transactions").path(index)

    private fun JsonNode.lastTx(): JsonNode = path("transactions").last()

    /** The plugin's `state` of each transaction, in Kill Bill's order, as "TYPE=STATE". */
    private fun JsonNode.states(): List<String> =
        path("transactions").mapIndexed { i, tx -> tx.path("transactionType").asText() + "=" + prop("state", i) }

    private fun JsonNode.status(index: Int = 0): String = tx(index).path("status").asText()

    private fun JsonNode.prop(
        key: String,
        index: Int = 0,
    ): String? =
        tx(index)
            .path("properties")
            .firstOrNull { it.path("key").asText() == key }
            ?.path("value")
            ?.asText()

    /** A customer with a card saved by a first hosted payment. */
    private fun customerWithCard(tenant: KillBillStack.TestTenant): Pair<String, String> {
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val first =
            stack.pay(
                tenant,
                account,
                pm,
                "PURCHASE",
                "100.00",
                properties =
                    mapOf(
                        "hosted" to "true",
                        "saveCard" to "true",
                    ),
            )
        fake.payInvoice(
            stack.payment(tenant, first.path("paymentId").asText(), withPluginInfo = true).prop("invoiceId")!!,
        )
        stack.awaitPayment(tenant, first.path("paymentId").asText()) { it.status() == "SUCCESS" }
        awaitCardActive(tenant, pm)
        return account to pm
    }

    private fun awaitCardActive(
        tenant: KillBillStack.TestTenant,
        pm: String,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (true) {
            val detail = stack.get("/1.0/kb/paymentMethods/$pm?withPluginInfo=true", tenant).json()
            val status =
                detail.path("pluginInfo").path("properties").firstOrNull {
                    it.path("key").asText() ==
                        "cardStatus"
                }
            if (status?.path("value")?.asText() == "ACTIVE") return
            check(System.nanoTime() < deadline) { "card never became ACTIVE: $detail" }
            Thread.sleep(300)
        }
    }

    @Test
    fun `hosted purchase saves the card, settles by webhook, and the card then pays a renewal`() {
        val tenant = stack.moneyTenant()
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)

        val pending =
            stack.pay(
                tenant,
                account,
                pm,
                "PURCHASE",
                "2290.00",
                properties =
                    mapOf(
                        "hosted" to "true",
                        "saveCard" to "true",
                    ),
            )
        val paymentId = pending.path("paymentId").asText()
        val withInfo = stack.payment(tenant, paymentId, withPluginInfo = true)
        assertThat(withInfo.status()).isEqualTo("PENDING")
        assertThat(withInfo.prop("formUrl")).startsWith("http")
        assertThat(withInfo.prop("state")).isEqualTo("PENDING")
        val invoiceId = withInfo.prop("invoiceId")!!

        fake.payInvoice(invoiceId)
        val paid = stack.awaitPayment(tenant, paymentId) { it.status() == "SUCCESS" }
        assertThat(stack.payment(tenant, paymentId, withPluginInfo = true).prop("state")).isEqualTo("SUCCESS")
        assertThat(paid.path("purchasedAmount").decimalValue()).isEqualByComparingTo("2290.00")
        awaitCardActive(tenant, pm)

        val renewal = stack.pay(tenant, account, pm, "PURCHASE", "2290.00")
        assertThat(renewal.status()).isEqualTo("SUCCESS")
        assertThat(fake.requestCount(MonobankPaths.WALLET_PAYMENT)).isGreaterThanOrEqualTo(1)

        // The token never leaves the plugin.
        val token = fake.invoice(invoiceId)!!.cardToken!!
        assertThat(stack.get("/1.0/kb/paymentMethods/$pm?withPluginInfo=true", tenant).body).doesNotContain(token)
        assertThat(
            stack.get("/1.0/kb/accounts/$account/paymentMethods?withPluginInfo=true", tenant).body,
        ).doesNotContain(token)
        assertThat(stack.killbillLogs()).doesNotContain(token)
    }

    @Test
    fun `a timeout after monobank charged is UNKNOWN, blocks a second charge, and resolves to one charge`() {
        val tenant = stack.moneyTenant()
        val (account, pm) = customerWithCard(tenant)
        val before = fake.requestCount(MonobankPaths.WALLET_PAYMENT)

        // monobank charges but the answer never comes; its webhook is held back until the test lets it go.
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        fake.injectFault(MonobankPaths.WALLET_PAYMENT, Fault.timeout())
        val unknown =
            try {
                stack.pay(tenant, account, pm, "PURCHASE", "50.00")
            } finally {
                fake.clearFaults()
            }
        assertThat(unknown.status()).isEqualTo("UNKNOWN")

        val second = stack.pay(tenant, account, pm, "PURCHASE", "50.00")
        assertThat(second.status()).isEqualTo("PLUGIN_FAILURE")
        assertThat(second.tx().path("gatewayErrorCode").asText()).isEqualTo("PREVIOUS_CHARGE_UNRESOLVED")

        // monobank's webhook for the charge that went through resolves it; Kill Bill's Janitor repairs the UNKNOWN.
        fake.setWebhookDelivery(WebhookDelivery.AUTOMATIC)
        fake.deliverWebhooksNow()
        val resolved =
            stack.awaitPayment(tenant, unknown.path("paymentId").asText(), Duration.ofSeconds(60)) {
                stack.payment(tenant, it.path("paymentId").asText(), withPluginInfo = true).status() == "SUCCESS"
            }
        assertThat(resolved.path("paymentId").asText()).isNotEmpty
        // Exactly one charge reached monobank: the refused second attempt never left the plugin.
        assertThat(fake.requestCount(MonobankPaths.WALLET_PAYMENT) - before).isEqualTo(1)
        assertThat(fake.invoices().count { it.viaWallet && it.amount == 5000L && it.status == "success" }).isEqualTo(1)
    }

    @Test
    fun `without a saved card and without hosted, a charge is declined and no monobank call is made`() {
        val tenant = stack.moneyTenant()
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val before = fake.requests().size

        val declined = stack.pay(tenant, account, pm, "PURCHASE", "10.00")

        assertThat(declined.status()).isEqualTo("PAYMENT_FAILURE")
        assertThat(declined.tx().path("gatewayErrorCode").asText()).isEqualTo("NO_ACTIVE_CARD")
        assertThat(fake.requests().size).isEqualTo(before)
        // Refused before the plugin stored it: no plugin info, so no `state` (a caller reads that as not refundable).
        val withInfo = stack.payment(tenant, declined.path("paymentId").asText(), withPluginInfo = true)
        assertThat(withInfo.status()).isEqualTo("PAYMENT_FAILURE")
        assertThat(withInfo.prop("state")).isNull()
    }

    @Test
    fun `a declined hosted payment fails in Kill Bill`() {
        val tenant = stack.moneyTenant()
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val pending = stack.pay(tenant, account, pm, "PURCHASE", "10.00", properties = mapOf("hosted" to "true"))
        val paymentId = pending.path("paymentId").asText()

        fake.failInvoice(stack.payment(tenant, paymentId, withPluginInfo = true).prop("invoiceId")!!)

        assertThat(
            stack
                .awaitPayment(tenant, paymentId) {
                    it.status() == "PAYMENT_FAILURE"
                }.status(),
        ).isEqualTo("PAYMENT_FAILURE")
    }

    @Test
    fun `a failed row stays FAILED in plugin info after Kill Bill's status is fixed to SUCCESS by hand`() {
        val tenant = stack.moneyTenant()
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val pending = stack.pay(tenant, account, pm, "PURCHASE", "12.00", properties = mapOf("hosted" to "true"))
        val paymentId = pending.path("paymentId").asText()
        fake.failInvoice(stack.payment(tenant, paymentId, withPluginInfo = true).prop("invoiceId")!!)
        val failed = stack.awaitPayment(tenant, paymentId) { it.status() == "PAYMENT_FAILURE" }
        assertThat(stack.payment(tenant, paymentId, withPluginInfo = true).prop("state")).isEqualTo("FAILED")

        // An operator fixes Kill Bill's transaction to SUCCESS (Kaui's "fix state"); the plugin's row stays FAILED.
        val fix =
            stack.fixTransactionState(
                tenant,
                paymentId,
                failed.tx().path("transactionId").asText(),
                transactionStatus = "SUCCESS",
                paymentState = "PURCHASE_SUCCESS",
            )
        assertThat(fix.status).describedAs(fix.body).isBetween(200, 299)

        val cancelsBefore = fake.requestCount(MonobankPaths.INVOICE_CANCEL)
        val fixed = stack.payment(tenant, paymentId, withPluginInfo = true)
        assertThat(fixed.status()).isEqualTo("SUCCESS")
        assertThat(fixed.prop("state")).isEqualTo("FAILED")
        // A second read (Kill Bill's on-the-fly Janitor runs on each) changes neither.
        val again = stack.payment(tenant, paymentId, withPluginInfo = true)
        assertThat(again.status()).isEqualTo("SUCCESS")
        assertThat(again.prop("state")).isEqualTo("FAILED")

        // The plugin refuses to refund it (nothing was charged), which is why a caller checks `state` first.
        stack.followUp(tenant, paymentId, "REFUND", "12.00")
        val refund = stack.payment(tenant, paymentId).lastTx()
        assertThat(refund.path("transactionType").asText()).isEqualTo("REFUND")
        assertThat(refund.path("status").asText()).isEqualTo("PLUGIN_FAILURE")
        assertThat(refund.path("gatewayErrorCode").asText()).isEqualTo("INVALID_OPERATION")
        assertThat(fake.requestCount(MonobankPaths.INVOICE_CANCEL)).isEqualTo(cancelsBefore)
        // The refused refund was never stored: the purchase still says FAILED, the refund has no state.
        assertThat(stack.payment(tenant, paymentId, withPluginInfo = true).states())
            .containsExactly("PURCHASE=FAILED", "REFUND=null")
    }

    @Test
    fun `a lost webhook is recovered by reading the payment with plugin info`() {
        val tenant = stack.moneyTenant()
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val pending = stack.pay(tenant, account, pm, "PURCHASE", "15.00", properties = mapOf("hosted" to "true"))
        val paymentId = pending.path("paymentId").asText()
        val invoiceId = stack.payment(tenant, paymentId, withPluginInfo = true).prop("invoiceId")!!

        fake.dropNextWebhooks(10) { it.invoiceId == invoiceId }
        fake.payInvoice(invoiceId)
        assertThat(stack.payment(tenant, paymentId).status()).isEqualTo("PENDING")

        val repaired = stack.payment(tenant, paymentId, withPluginInfo = true)
        assertThat(repaired.status()).isEqualTo("SUCCESS")
        fake.clearFaults()
    }

    @Test
    fun `a hosted invoice payment marks the Kill Bill invoice paid`() {
        val tenant = stack.moneyTenant()
        val account = stack.createAccount(tenant)
        // not the account's default method: Kill Bill's auto-pay would charge the new invoice itself (declined, no
        // card) and race the page for the invoice's first payment
        val pm = stack.addPaymentMethod(tenant, account, isDefault = false)
        val charge =
            json.writeValueAsString(
                listOf(
                    mapOf(
                        "accountId" to account,
                        "amount" to "12900.00",
                        "currency" to "UAH",
                        "description" to "Audit",
                    ),
                ),
            )
        val created =
            stack.post(
                "/1.0/kb/invoices/charges/$account?autoCommit=true",
                charge,
                "application/json",
                tenant,
            )
        assertThat(created.status).describedAs(created.body).isIn(200, 201)
        val invoiceId =
            created
                .json()
                .path(0)
                .path("invoiceId")
                .asText()

        val body =
            json.writeValueAsString(
                mapOf(
                    "accountId" to account,
                    "purchasedAmount" to "12900.00",
                    "paymentMethodId" to pm,
                ),
            )
        val response =
            stack.post(
                "/1.0/kb/invoices/$invoiceId/payments?externalPayment=false" +
                    "&pluginProperty=hosted%3Dtrue&pluginProperty=callerRef%3Dcheckout-1",
                body,
                "application/json",
                tenant,
            )
        assertThat(response.status).describedAs(response.body).isIn(201, 402)
        val payments = stack.get("/1.0/kb/invoices/$invoiceId/payments?withPluginInfo=true", tenant).json()
        val paymentId = payments.path(0).path("paymentId").asText()
        val withInfo = stack.payment(tenant, paymentId, withPluginInfo = true)
        assertThat(withInfo.status()).isEqualTo("PENDING")
        assertThat(withInfo.prop("callerRef")).isEqualTo("checkout-1")

        fake.payInvoice(withInfo.prop("invoiceId")!!)
        stack.awaitPayment(tenant, paymentId) { it.status() == "SUCCESS" }

        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        var invoice = stack.get("/1.0/kb/invoices/$invoiceId", tenant).json()
        while (invoice.path("balance").decimalValue().signum() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(300)
            invoice = stack.get("/1.0/kb/invoices/$invoiceId", tenant).json()
        }
        assertThat(invoice.path("balance").decimalValue()).isEqualByComparingTo("0")
    }

    @Test
    fun `an unpaid hosted page can be cancelled, one paid at the same moment answers 409 and stays paid`() {
        val tenant = stack.moneyTenant()
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)

        val unpaid =
            stack
                .pay(
                    tenant,
                    account,
                    pm,
                    "PURCHASE",
                    "20.00",
                    properties = mapOf("hosted" to "true"),
                ).path("paymentId")
                .asText()
        val cancelled = stack.post("/plugins/killbill-monobank/payments/$unpaid/cancel", "", "application/json", tenant)
        assertThat(cancelled.status).describedAs(cancelled.body).isEqualTo(200)
        assertThat(
            stack
                .awaitPayment(tenant, unpaid) {
                    it.status() == "PAYMENT_FAILURE"
                }.status(),
        ).isEqualTo("PAYMENT_FAILURE")

        val paid =
            stack
                .pay(
                    tenant,
                    account,
                    pm,
                    "PURCHASE",
                    "20.00",
                    properties = mapOf("hosted" to "true"),
                ).path("paymentId")
                .asText()
        // The webhook is held back, so it is monobank itself that refuses to remove the paid page.
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        val paidInvoice = stack.payment(tenant, paid, withPluginInfo = true).prop("invoiceId")!!
        fake.payInvoice(paidInvoice)
        val removeCalls = fake.requestCount(MonobankPaths.INVOICE_REMOVE)
        val conflict = stack.post("/plugins/killbill-monobank/payments/$paid/cancel", "", "application/json", tenant)
        assertThat(conflict.status).describedAs(conflict.body).isEqualTo(409)
        assertThat(fake.requestCount(MonobankPaths.INVOICE_REMOVE) - removeCalls).isEqualTo(1)
        assertThat(fake.invoice(paidInvoice)!!.status).isEqualTo("success")
        assertThat(stack.awaitPayment(tenant, paid) { it.status() == "SUCCESS" }.status()).isEqualTo("SUCCESS")

        val anonymous = stack.get("/plugins/killbill-monobank/pubkey", tenant = null, auth = false)
        assertThat(anonymous.status).isEqualTo(401)
        val key = stack.get("/plugins/killbill-monobank/pubkey", tenant)
        assertThat(key.status).isEqualTo(200)
        assertThat(key.json().path("key").asText()).isEqualTo(fake.signer.publicKey)
    }

    @Test
    fun `cancelling a page needs credentials, the transition permission and the page's own tenant`() {
        val tenant = stack.moneyTenant()
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val page =
            stack
                .pay(tenant, account, pm, "PURCHASE", "21.00", properties = mapOf("hosted" to "true"))
                .path("paymentId")
                .asText()
        val path = "/plugins/killbill-monobank/payments/$page/cancel"
        val removeCalls = fake.requestCount(MonobankPaths.INVOICE_REMOVE)

        assertThat(stack.post(path, "", "application/json", tenant, auth = false).status).isEqualTo(401)
        val reader = stack.createUser(listOf("account:create"))
        assertThat(stack.post(path, "", "application/json", tenant, user = reader).status).isEqualTo(403)
        val other = stack.createTenant()
        assertThat(stack.post(path, "", "application/json", other).status).isEqualTo(404)
        val unknown = "/plugins/killbill-monobank/payments/${java.util.UUID.randomUUID()}/cancel"
        assertThat(stack.post(unknown, "", "application/json", tenant).status).isEqualTo(404)
        assertThat(fake.requestCount(MonobankPaths.INVOICE_REMOVE)).isEqualTo(removeCalls)
        assertThat(stack.payment(tenant, page).status()).isEqualTo("PENDING")
    }

    @Test
    fun `partial then full refund of a saved-card charge, and a refund above what is left is refused`() {
        val tenant = stack.moneyTenant()
        val (account, pm) = customerWithCard(tenant)
        val payment = stack.pay(tenant, account, pm, "PURCHASE", "100.00")
        val paymentId = payment.path("paymentId").asText()
        assertThat(payment.status()).isEqualTo("SUCCESS")
        val charged = stack.payment(tenant, paymentId, withPluginInfo = true)
        assertThat(charged.prop("state")).isEqualTo("SUCCESS")

        // The refund stays processing at monobank until the test settles it: each entry reads its own state.
        fake.setRefundSettlement(RefundSettlement.MANUAL)
        try {
            stack.followUp(tenant, paymentId, "REFUND", "30.00")
            val refunding = stack.payment(tenant, paymentId, withPluginInfo = true)
            assertThat(refunding.states()).containsExactly("PURCHASE=SUCCESS", "REFUND=PENDING")
            fake.settleRefund(charged.prop("invoiceId")!!, refunding.prop("reference", 1), true)
        } finally {
            fake.setRefundSettlement(RefundSettlement.AUTOMATIC)
        }
        stack.awaitPayment(tenant, paymentId) {
            it.lastTx().path("status").asText() == "SUCCESS" &&
                it.path("refundedAmount").decimalValue().signum() > 0
        }
        assertThat(stack.payment(tenant, paymentId, withPluginInfo = true).states())
            .containsExactly("PURCHASE=SUCCESS", "REFUND=SUCCESS")
        val tooMuch = stack.followUp(tenant, paymentId, "REFUND", "80.00")
        assertThat(tooMuch.status).isNotEqualTo(201)
        val refused = stack.payment(tenant, paymentId).lastTx()
        assertThat(refused.path("transactionType").asText()).isEqualTo("REFUND")
        assertThat(refused.path("status").asText()).isEqualTo("PLUGIN_FAILURE")
        assertThat(refused.path("gatewayErrorCode").asText()).isEqualTo("INVALID_OPERATION")
        stack.followUp(tenant, paymentId, "REFUND", "70.00")
        val refunded =
            stack.awaitPayment(tenant, paymentId) {
                it.path("refundedAmount").decimalValue().compareTo(java.math.BigDecimal("100")) ==
                    0
            }
        assertThat(refunded.path("refundedAmount").decimalValue()).isEqualByComparingTo("100")
    }

    @Test
    fun `authorize with a hosted hold, capture part of it, and void another hold`() {
        val tenant = stack.moneyTenant()
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)

        val auth = stack.pay(tenant, account, pm, "AUTHORIZE", "300.00", properties = mapOf("hosted" to "true"))
        val authId = auth.path("paymentId").asText()
        fake.payInvoice(stack.payment(tenant, authId, withPluginInfo = true).prop("invoiceId")!!)
        stack.awaitPayment(tenant, authId) { it.status() == "SUCCESS" }
        stack.followUp(tenant, authId, "CAPTURE", "250.00")
        val captured = stack.awaitPayment(tenant, authId) { it.path("capturedAmount").decimalValue().signum() > 0 }
        assertThat(captured.path("capturedAmount").decimalValue()).isEqualByComparingTo("250")
        assertThat(stack.payment(tenant, authId, withPluginInfo = true).states())
            .containsExactly("AUTHORIZE=SUCCESS", "CAPTURE=SUCCESS")

        val second = stack.pay(tenant, account, pm, "AUTHORIZE", "100.00", properties = mapOf("hosted" to "true"))
        val secondId = second.path("paymentId").asText()
        fake.payInvoice(stack.payment(tenant, secondId, withPluginInfo = true).prop("invoiceId")!!)
        stack.awaitPayment(tenant, secondId) { it.status() == "SUCCESS" }
        stack.followUp(tenant, secondId, "VOID")
        val voided =
            stack.awaitPayment(tenant, secondId) {
                it.lastTx().path("transactionType").asText() == "VOID" &&
                    it.lastTx().path("status").asText() == "SUCCESS"
            }
        assertThat(voided.path("authAmount").decimalValue()).isEqualByComparingTo("0")
        assertThat(stack.payment(tenant, secondId, withPluginInfo = true).states())
            .containsExactly("AUTHORIZE=SUCCESS", "VOID=SUCCESS")
        assertThat(
            fake.invoice(stack.payment(tenant, secondId, withPluginInfo = true).prop("invoiceId")!!)!!.status,
        ).isEqualTo("reversed")
    }

    @Test
    fun `a forged webhook is ignored and changes nothing`() {
        val tenant = stack.moneyTenant()
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val pending = stack.pay(tenant, account, pm, "PURCHASE", "33.00", properties = mapOf("hosted" to "true"))
        val paymentId = pending.path("paymentId").asText()

        val invoiceId = stack.payment(tenant, paymentId, withPluginInfo = true).prop("invoiceId")!!
        val failedBefore = counters().path("signatureFailed").asLong()
        fake.corruptNextWebhookSignatures(10) { it.invoiceId == invoiceId }
        fake.payInvoice(invoiceId)
        val delivered = fake.awaitWebhooks(invoiceId, 1, Duration.ofSeconds(10))
        assertThat(delivered).isNotEmpty
        assertThat(counters().path("signatureFailed").asLong()).isGreaterThan(failedBefore)
        assertThat(stack.payment(tenant, paymentId).status()).isEqualTo("PENDING")
    }

    @Test
    fun `the open health route shows no counters, and webhook bodies above the limit are refused`() {
        val open = stack.get("/plugins/killbill-monobank/healthcheck", auth = false)
        assertThat(open.json().path("details").has("counters")).isFalse()
        assertThat(counters().has("stateConflict")).isTrue()

        val tooLarge =
            stack.post(
                "/plugins/killbill-monobank/notification",
                "x".repeat(70 * 1024),
                "application/json",
                auth = false,
            )
        assertThat(tooLarge.status).isEqualTo(413)
    }

    @Test
    fun `a tenant without monobank settings is refused before anything reaches monobank`() {
        val tenant = stack.createTenant()
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val before = fake.requests().size

        val refused = stack.pay(tenant, account, pm, "PURCHASE", "10.00", properties = mapOf("hosted" to "true"))

        assertThat(refused.status()).isEqualTo("PLUGIN_FAILURE")
        assertThat(refused.tx().path("gatewayErrorCode").asText()).isEqualTo("CONFIG_INVALID")
        assertThat(fake.requests().size).isEqualTo(before)
    }

    @Test
    fun `deleting a payment method removes its card at monobank, and Kill Bill's refresh keeps the others`() {
        val tenant = stack.moneyTenant()
        val (account, pm) = customerWithCard(tenant)
        val second = stack.addPaymentMethod(tenant, account)

        val refreshed = stack.put("/1.0/kb/accounts/$account/paymentMethods/refresh", tenant)
        assertThat(refreshed.status).describedAs(refreshed.body).isIn(200, 201, 204)
        val methods = stack.get("/1.0/kb/accounts/$account/paymentMethods", tenant).json()
        assertThat(methods.map { it.path("paymentMethodId").asText() }).contains(pm, second)

        val removals = fake.requestCount(MonobankPaths.WALLET_CARD)
        val deleted = stack.delete("/1.0/kb/paymentMethods/$pm?forceDefaultPmDeletion=true", tenant)
        assertThat(deleted.status).describedAs(deleted.body).isIn(200, 204)
        assertThat(fake.requestCount(MonobankPaths.WALLET_CARD) - removals).isEqualTo(1)
        val left = stack.get("/1.0/kb/accounts/$account/paymentMethods", tenant).json()
        assertThat(left.map { it.path("paymentMethodId").asText() }).containsExactly(second)
    }

    @Test
    fun `an account in euro or dollars is refused with a clear code, and monobank is never called`() {
        val tenant = stack.moneyTenant()
        val before = fake.requests().size
        for (currency in listOf("EUR", "USD")) {
            val account = stack.createAccount(tenant, currency = currency)
            val pm = stack.addPaymentMethod(tenant, account)
            for (properties in listOf(mapOf("hosted" to "true"), emptyMap())) {
                val refused =
                    stack.pay(
                        tenant,
                        account,
                        pm,
                        "PURCHASE",
                        "5.00",
                        currency = currency,
                        properties = properties,
                    )
                assertThat(refused.status()).isEqualTo("PLUGIN_FAILURE")
                assertThat(refused.tx().path("gatewayErrorCode").asText()).isEqualTo("CURRENCY_NOT_SUPPORTED")
            }
        }
        assertThat(fake.requests().size).isEqualTo(before)
    }
}
