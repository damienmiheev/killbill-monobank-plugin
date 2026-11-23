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
import io.github.damienmiheev.monobank.testkit.SplitReceiver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/**
 * Receipt items from the Kill Bill invoice (design A5) through a real Kill Bill: the plugin reads the invoice inside
 * an auto-pay charge, a Kill Bill retry and a hosted invoice payment, and names the item after the catalogue's plan
 * or the external charge.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvoiceItemsFlowIT {
    private lateinit var fake: FakeMonobankAcquiring
    private lateinit var stack: KillBillStack
    private lateinit var tenant: KillBillStack.TestTenant
    private val json = ObjectMapper()

    @BeforeAll
    fun start() {
        fake =
            FakeMonobankAcquiring
                .builder()
                .fiscalization(true)
                .splitPayments(true)
                .build()
                .start()
        stack = KillBillStack(fake = fake).start()
        tenant =
            stack.moneyTenant(
                "org.killbill.billing.plugin.monobank.splitReceiverId=${SplitReceiver.DEFAULT.id}\n" +
                    "org.killbill.billing.plugin.monobank.basketFromInvoice=true",
            )
        // A plugin failure is retried by Kill Bill a minute later, once (the tenant's own setting).
        val config =
            json.writeValueAsString(
                mapOf(
                    "org.killbill.payment.failure.retry.start.sec" to "60",
                    "org.killbill.payment.failure.retry.multiplier" to "1",
                    "org.killbill.payment.failure.retry.max.attempts" to "1",
                ),
            )
        assertThat(
            stack.post("/1.0/kb/tenants/uploadPerTenantConfig", config, "text/plain", tenant).status,
        ).isEqualTo(201)
        val catalog = stack.post("/1.0/kb/catalog/xml", CATALOG, "text/xml", tenant)
        assertThat(catalog.status).describedAs(catalog.body).isEqualTo(201)
        // Kill Bill applies a tenant's new catalogue and settings from its broadcast job
        Thread.sleep(BROADCAST_MS)
    }

    @AfterAll
    fun stop() {
        if (::stack.isInitialized) stack.close()
        if (::fake.isInitialized) fake.close()
    }

    @AfterEach
    fun resetFake() {
        fake.clearFaults()
    }

    private fun JsonNode.prop(key: String): String? =
        path("transactions")
            .last()
            .path("properties")
            .firstOrNull { it.path("key").asText() == key }
            ?.path("value")
            ?.asText()

    /** An account whose default payment method has a card saved by a first hosted payment (no invoice). */
    private fun customerWithCard(): String {
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val first =
            stack.pay(
                tenant,
                account,
                pm,
                "PURCHASE",
                "1.00",
                properties =
                    mapOf(
                        "hosted" to "true",
                        "saveCard" to "true",
                    ),
            )
        val paymentId = first.path("paymentId").asText()
        fake.payInvoice(stack.payment(tenant, paymentId, withPluginInfo = true).prop("invoiceId")!!)
        stack.awaitPayment(tenant, paymentId) {
            it
                .path("transactions")
                .last()
                .path("status")
                .asText() == "SUCCESS"
        }
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (!cardActive(pm)) {
            check(System.nanoTime() < deadline) { "card never saved" }
            Thread.sleep(POLL_MS)
        }
        return account
    }

    private fun cardActive(pm: String): Boolean =
        stack
            .get("/1.0/kb/paymentMethods/$pm?withPluginInfo=true", tenant)
            .json()
            .path("pluginInfo")
            .path("properties")
            .any { it.path("key").asText() == "cardStatus" && it.path("value").asText() == "ACTIVE" }

    /** The basket items of the wallet payments sent for [amountMinor], oldest first. */
    private fun walletBaskets(amountMinor: Long): List<JsonNode> =
        fake
            .requests()
            .filter { it.path == MonobankPaths.WALLET_PAYMENT }
            .map { json.readTree(it.body) }
            .filter { it.path("amount").asLong() == amountMinor }
            .map { it.path("merchantPaymInfo") }

    private fun awaitWallet(
        amountMinor: Long,
        count: Int,
    ): List<JsonNode> {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (walletBaskets(amountMinor).size < count) {
            check(System.nanoTime() < deadline) { "Kill Bill never charged $amountMinor $count time(s)" }
            Thread.sleep(POLL_MS)
        }
        return walletBaskets(amountMinor)
    }

    private fun subscribe(
        account: String,
        plan: String,
    ) {
        val body = json.writeValueAsString(mapOf("accountId" to account, "planName" to plan))
        val created = stack.post("/1.0/kb/subscriptions", body, "application/json", tenant)
        assertThat(created.status).describedAs(created.body).isEqualTo(201)
    }

    @Test
    fun `auto-pay charges the saved card with the plan's pretty name, or its own name without one`() {
        val account = customerWithCard()
        subscribe(account, "pretty-month")
        val pretty = awaitWallet(229000, 1).single()
        val item = pretty.path("basketOrder").single()
        assertThat(item.path("name").asText()).isEqualTo("Seqra Monitoring, 1 місяць")
        assertThat(item.path("qty").decimalValue()).isEqualByComparingTo("1")
        assertThat(item.path("sum").asLong()).isEqualTo(229000)
        assertThat(item.path("splitReceiverId").asText()).isEqualTo(SplitReceiver.DEFAULT.id)
        assertThat(pretty.path("destination").asText()).isEqualTo("Seqra Monitoring, 1 місяць")

        subscribe(customerWithCard(), "plain-month")
        assertThat(
            awaitWallet(4900, 1)
                .single()
                .path("basketOrder")
                .single()
                .path("name")
                .asText(),
        ).isEqualTo("plain-month")
    }

    @Test
    fun `a Kill Bill retry after a plugin failure carries the invoice's item`() {
        val account = customerWithCard()
        // monobank refuses the first charge as a bad request (nothing charged): a plugin failure Kill Bill retries
        fake.injectFault(
            MonobankPaths.WALLET_PAYMENT,
            Fault.response(400, """{"errCode":"BAD_REQUEST","errText":"try again"}"""),
            1,
        ) { json.readTree(it.body).path("amount").asLong() == 99000L }
        subscribe(account, "retry-month")
        awaitWallet(99000, 1)
        val now =
            stack
                .get("/1.0/kb/test/clock", tenant)
                .json()
                .path("currentUtcTime")
                .asText()
        val later =
            java.time.Instant
                .parse(now)
                .plus(Duration.ofMinutes(2))
        assertThat(stack.post("/1.0/kb/test/clock?requestedDate=$later", "", "application/json", tenant).status)
            .isIn(200, 201, 204)
        val retried = awaitWallet(99000, 2)
        assertThat(
            retried.map {
                it
                    .path("basketOrder")
                    .single()
                    .path("name")
                    .asText()
            },
        ).containsExactly("Retry plan", "Retry plan")
    }

    @Test
    fun `a hosted payment of an external charge is named by the charge's description`() {
        val account = stack.createAccount(tenant)
        val pm = stack.addPaymentMethod(tenant, account)
        val charge =
            json.writeValueAsString(
                listOf(
                    mapOf(
                        "accountId" to account,
                        "amount" to "990.00",
                        "currency" to "UAH",
                        "description" to "Seqra Audit, 1 домен",
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
                    "purchasedAmount" to "990.00",
                    "paymentMethodId" to pm,
                ),
            )
        val paid =
            stack.post(
                "/1.0/kb/invoices/$invoiceId/payments?pluginProperty=hosted%3Dtrue",
                body,
                "application/json",
                tenant,
            )
        assertThat(paid.status).describedAs(paid.body).isIn(201, 402)
        val page = json.readTree(fake.requests().last { it.path == MonobankPaths.INVOICE_CREATE }.body)
        val item = page.path("merchantPaymInfo").path("basketOrder").single()
        assertThat(item.path("name").asText()).isEqualTo("Seqra Audit, 1 домен")
        assertThat(item.path("qty").decimalValue()).isEqualByComparingTo("1")
        assertThat(item.path("sum").asLong()).isEqualTo(99000)
        val paymentId =
            stack
                .get(
                    "/1.0/kb/invoices/$invoiceId/payments",
                    tenant,
                ).json()
                .path(0)
                .path("paymentId")
                .asText()
        assertThat(stack.payment(tenant, paymentId, withPluginInfo = true).prop("amountStep")).isEqualTo("99")
    }

    private companion object {
        const val POLL_MS = 300L
        const val BROADCAST_MS = 6000L

        val CATALOG =
            """
            <catalog xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xsi:noNamespaceSchemaLocation="CatalogSchema.xsd">
              <effectiveDate>2020-01-01T00:00:00+00:00</effectiveDate>
              <catalogName>it</catalogName>
              <recurringBillingMode>IN_ADVANCE</recurringBillingMode>
              <currencies><currency>UAH</currency></currencies>
              <products><product name="monitoring"><category>BASE</category></product></products>
              <rules>
                <changePolicy><changePolicyCase><policy>IMMEDIATE</policy></changePolicyCase></changePolicy>
                <cancelPolicy><cancelPolicyCase><policy>IMMEDIATE</policy></cancelPolicyCase></cancelPolicy>
                <priceList><priceListCase><toPriceList>DEFAULT</toPriceList></priceListCase></priceList>
              </rules>
              <plans>
                ${plan("pretty-month", "Seqra Monitoring, 1 місяць", "2290.00")}
                ${plan("plain-month", null, "49.00")}
                ${plan("retry-month", "Retry plan", "990.00")}
              </plans>
              <priceLists>
                <defaultPriceList name="DEFAULT"><plans>
                  <plan>pretty-month</plan><plan>plain-month</plan><plan>retry-month</plan>
                </plans></defaultPriceList>
              </priceLists>
            </catalog>
            """.trimIndent()

        fun plan(
            name: String,
            pretty: String?,
            price: String,
        ): String =
            """<plan name="$name"${pretty?.let { " prettyName=\"$it\"" } ?: ""}><product>monitoring</product>""" +
                """<finalPhase type="EVERGREEN"><duration><unit>UNLIMITED</unit></duration><recurring>""" +
                """<billingPeriod>MONTHLY</billingPeriod><recurringPrice><price><currency>UAH</currency>""" +
                """<value>$price</value></price></recurringPrice></recurring></finalPhase></plan>"""
    }
}
