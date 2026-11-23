/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.damienmiheev.killbill.monobank.api.transactionProperties
import io.github.damienmiheev.killbill.monobank.db.TxState
import io.github.damienmiheev.monobank.testkit.MonobankPaths
import io.github.damienmiheev.monobank.testkit.SplitReceiver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.killbill.billing.invoice.api.InvoiceItemType
import org.killbill.billing.payment.api.TransactionType
import java.math.BigDecimal
import java.util.UUID

/** Receipt items from the Kill Bill invoice (design A5): what reaches monobank, and the refunds of such a charge. */
class InvoiceItemsIT : ServiceHarness() {
    override val extraTenantConfig = "org.killbill.billing.plugin.monobank.basketFromInvoice=true"

    private val json = ObjectMapper()

    /** Kill Bill's invoices for this test class, by id. */
    private val invoices = java.util.concurrent.ConcurrentHashMap<UUID, InvoiceView>()

    private fun renewal(
        account: UUID,
        amount: String,
        name: String? = "Seqra Monitoring, 1 місяць",
    ): UUID {
        val id = UUID.randomUUID()
        invoices[id] =
            InvoiceView(
                id,
                account,
                "UAH",
                listOf(
                    InvoiceView.Line(
                        UUID.randomUUID(),
                        InvoiceItemType.RECURRING,
                        BigDecimal(amount),
                        null,
                        null,
                        "monitoring-month",
                        name,
                        "monitoring",
                        "monitoring",
                    ),
                ),
            )
        invoiceReader = { _, _, invoiceId -> invoices[invoiceId] ?: error("no invoice $invoiceId") }
        return id
    }

    /** The JSON body of the last request to [path] at the fake. */
    private fun lastBody(path: String): JsonNode = json.readTree(fake.requests().last { it.path == path }.body)

    private fun basketOf(body: JsonNode): JsonNode = body.path("merchantPaymInfo").path("basketOrder")

    @Test
    fun `a saved-card charge of an invoice carries the invoice's item, 1 x the amount, and the step in plugin info`() {
        val (account, pm) = cardHolder()
        val invoice = renewal(account, "2290.00")
        val payment = UUID.randomUUID()
        val row =
            stored(
                payments.charge(
                    op(TransactionType.PURCHASE, account, payment, pm, "2290.00", "IPCD_INVOICE_ID" to "$invoice"),
                ),
            )
        assertThat(row.state).isEqualTo(TxState.SUCCESS)
        val body = lastBody(MonobankPaths.WALLET_PAYMENT)
        val item = basketOf(body).single()
        assertThat(item.path("name").asText()).isEqualTo("Seqra Monitoring, 1 місяць")
        assertThat(item.path("qty").decimalValue()).isEqualByComparingTo("1")
        assertThat(item.path("sum").asLong()).isEqualTo(229000)
        assertThat(item.path("splitReceiverId").asText()).isEqualTo(SplitReceiver.DEFAULT.id)
        assertThat(body.path("merchantPaymInfo").path("destination").asText()).isEqualTo("Seqra Monitoring, 1 місяць")
        assertThat(transactionProperties(row).associate { it.key to it.value }["amountStep"]).isEqualTo("229")

        // a part refund: the same item, quantity = amount ÷ price; an amount off the step never reaches monobank
        val cancels = fake.requests().count { it.path == MonobankPaths.INVOICE_CANCEL }
        val off = payments.refund(op(TransactionType.REFUND, account, payment, pm, "100.00"))
        assertThat((off as Answer.Refused).code).isEqualTo(PaymentService.INVALID_BASKET)
        assertThat(off.message).contains("amount step 229")
        assertThat(fake.requests().count { it.path == MonobankPaths.INVOICE_CANCEL }).isEqualTo(cancels)

        val part = stored(payments.refund(op(TransactionType.REFUND, account, payment, pm, "229.00")))
        assertThat(awaitRow(part) { it.state.isFinal }.state).isEqualTo(TxState.SUCCESS)
        val refunded = json.readTree(fake.requests().last { it.path == MonobankPaths.INVOICE_CANCEL }.body)
        val back = refunded.path("items").single()
        assertThat(back.path("name").asText()).isEqualTo("Seqra Monitoring, 1 місяць")
        assertThat(back.path("qty").decimalValue()).isEqualByComparingTo("0.1")
        assertThat(back.path("sum").asLong()).isEqualTo(229000)

        val rest = stored(payments.refund(op(TransactionType.REFUND, account, payment, pm, "2061.00")))
        assertThat(awaitRow(rest) { it.state.isFinal }.state).isEqualTo(TxState.SUCCESS)
        assertThat(
            json
                .readTree(fake.requests().last { it.path == MonobankPaths.INVOICE_CANCEL }.body)
                .path("items")
                .single()
                .path("qty")
                .decimalValue(),
        ).isEqualByComparingTo("0.9")
    }

    @Test
    fun `a hosted page of an invoice in kopecks carries the item, and the caller's destination and basket win`() {
        val account = UUID.randomUUID()
        val pm = UUID.randomUUID()
        paymentMethods.add(tenantId, account, pm)
        val invoice = renewal(account, "2083.45")
        payments.charge(
            op(
                TransactionType.PURCHASE,
                account,
                UUID.randomUUID(),
                pm,
                "2083.45",
                "hosted" to "true",
                "IPCD_INVOICE_ID" to "$invoice",
                "destination" to "Оплата Seqra",
            ),
        )
        val body = lastBody(MonobankPaths.INVOICE_CREATE)
        assertThat(basketOf(body).single().path("name").asText()).isEqualTo("Seqra Monitoring, 1 місяць")
        assertThat(basketOf(body).single().path("sum").asLong()).isEqualTo(208345)
        assertThat(body.path("merchantPaymInfo").path("destination").asText()).isEqualTo("Оплата Seqra")

        // another invoice: the first one's page is still open, and the plugin allows one open charge per invoice
        val second = renewal(account, "2083.45")
        val own =
            """[{"name":"Own","qty":1,"sum":208345,"code":"own",""" +
                """"splitReceiverId":"${SplitReceiver.DEFAULT.id}"}]"""
        payments.charge(
            op(
                TransactionType.PURCHASE,
                account,
                UUID.randomUUID(),
                pm,
                "2083.45",
                "hosted" to "true",
                "IPCD_INVOICE_ID" to "$second",
                "basketOrder" to own,
            ),
        )
        assertThat(basketOf(lastBody(MonobankPaths.INVOICE_CREATE)).single().path("name").asText()).isEqualTo("Own")
    }

    @Test
    fun `what can't be built gets the default item and is counted, and no invoice gets it uncounted`() {
        val (account, pm) = cardHolder()
        val before = metrics.basketFallback.get()

        // another account's invoice
        val stranger = renewal(UUID.randomUUID(), "10.00")
        payments.charge(
            op(
                TransactionType.PURCHASE,
                account,
                UUID.randomUUID(),
                pm,
                "10.00",
                "IPCD_INVOICE_ID" to "$stranger",
            ),
        )
        assertThat(basketOf(lastBody(MonobankPaths.WALLET_PAYMENT)).single().path("sum").asLong()).isEqualTo(100)
        assertThat(metrics.basketFallback.get()).isEqualTo(before + 1)

        // an invoice Kill Bill can't read
        payments.charge(
            op(
                TransactionType.PURCHASE,
                account,
                UUID.randomUUID(),
                pm,
                "10.00",
                "IPCD_INVOICE_ID" to "${UUID.randomUUID()}",
            ),
        )
        val default = basketOf(lastBody(MonobankPaths.WALLET_PAYMENT)).single()
        assertThat(default.path("sum").asLong()).isEqualTo(100)
        assertThat(default.path("qty").decimalValue()).isEqualByComparingTo("10.00")
        assertThat(metrics.basketFallback.get()).isEqualTo(before + 2)
        assertThat(metrics.snapshot()["basketFallback"]).isEqualTo(before + 2)

        // a payment that pays no invoice: the default item, nothing counted
        val plain = stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "10.00")))
        assertThat(basketOf(lastBody(MonobankPaths.WALLET_PAYMENT)).single().path("sum").asLong()).isEqualTo(100)
        assertThat(metrics.basketFallback.get()).isEqualTo(before + 2)
        assertThat(transactionProperties(plain).associate { it.key to it.value }["amountStep"]).isEqualTo("1")
    }

    @Test
    fun `a hold of an invoice is captured in part, then that capture refunded in part`() {
        val (account, pm) = cardHolder()
        val invoice = renewal(account, "1000.00")
        val payment = UUID.randomUUID()
        val auth =
            stored(
                payments.charge(
                    op(TransactionType.AUTHORIZE, account, payment, pm, "1000.00", "IPCD_INVOICE_ID" to "$invoice"),
                ),
            )
        val held = if (auth.state == TxState.PENDING) payments.refreshIfOpen(auth) else auth
        assertThat(held.state).isEqualTo(TxState.SUCCESS)
        assertThat(transactionProperties(held).associate { it.key to it.value }["amountStep"]).isEqualTo("100")

        val capture = stored(payments.capture(op(TransactionType.CAPTURE, account, payment, pm, "500.00")))
        val finalized = json.readTree(fake.requests().last { it.path == MonobankPaths.INVOICE_FINALIZE }.body)
        assertThat(
            finalized
                .path("items")
                .single()
                .path("qty")
                .decimalValue(),
        ).isEqualByComparingTo("0.5")
        assertThat(capture.state).isEqualTo(TxState.SUCCESS)
        assertThat(transactionProperties(capture).associate { it.key to it.value }["amountStep"]).isEqualTo("100")
        fake.settleFinalize(capture.invoiceId!!)

        val refund = stored(payments.refund(op(TransactionType.REFUND, account, payment, pm, "100.00")))
        assertThat(awaitRow(refund) { it.state.isFinal }.state).isEqualTo(TxState.SUCCESS)
        val back = json.readTree(fake.requests().last { it.path == MonobankPaths.INVOICE_CANCEL }.body)
        assertThat(
            back
                .path("items")
                .single()
                .path("qty")
                .decimalValue(),
        ).isEqualByComparingTo("0.1")
        assertThat(
            back
                .path("items")
                .single()
                .path("sum")
                .asLong(),
        ).isEqualTo(100000)
    }
}
