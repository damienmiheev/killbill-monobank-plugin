/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import io.github.damienmiheev.killbill.monobank.config.TenantSettings
import io.github.damienmiheev.killbill.monobank.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.killbill.billing.invoice.api.InvoiceItemType
import java.math.BigDecimal
import java.util.UUID

class InvoiceBasketTest {
    private val account = UUID.randomUUID()
    private val settings =
        (
            TenantSettings.parse(
                listOf(
                    "org.killbill.billing.plugin.monobank.merchantToken=t",
                    "org.killbill.billing.plugin.monobank.webhookUrl=https://example.com/h",
                    "org.killbill.billing.plugin.monobank.basketItemCode=seqra-item",
                    "org.killbill.billing.plugin.monobank.splitReceiverId=rcv",
                    "org.killbill.billing.plugin.monobank.basketFromInvoice=true",
                ).joinToString("\n"),
                null,
            ) { null } as TenantSettings.ParseResult.Valid
        ).settings

    private fun uah(minor: Long) = Money(minor, Money.currency("UAH"))

    private fun line(
        type: InvoiceItemType,
        amount: String,
        plan: String? = null,
        prettyPlan: String? = plan,
        description: String? = null,
        linked: UUID? = null,
        id: UUID = UUID.randomUUID(),
        product: String? = null,
        prettyProduct: String? = product,
    ) = InvoiceView.Line(id, type, BigDecimal(amount), linked, description, plan, prettyPlan, product, prettyProduct)

    private fun invoice(
        vararg lines: InvoiceView.Line,
        currency: String = "UAH",
        accountId: UUID = account,
    ) = InvoiceView(UUID.randomUUID(), accountId, currency, lines.toList())

    private fun built(
        invoice: InvoiceView,
        amountMinor: Long,
    ) = (Baskets.fromInvoice(invoice, settings, account, uah(amountMinor)) as InvoiceBasket.Built).item

    private fun fallback(
        invoice: InvoiceView,
        amountMinor: Long,
    ) = (Baskets.fromInvoice(invoice, settings, account, uah(amountMinor)) as InvoiceBasket.Fallback).reason

    @Test
    fun `a renewal is one item named by the plan's pretty name, 1 x the amount, with the tenant's code`() {
        val item =
            built(
                invoice(line(InvoiceItemType.RECURRING, "2290.00", "monitoring-month", "Seqra Monitoring, 1 місяць")),
                229000,
            )
        assertThat(item.name).isEqualTo("Seqra Monitoring, 1 місяць")
        assertThat(item.qty).isEqualByComparingTo("1")
        assertThat(item.sum).isEqualTo(229000)
        assertThat(item.total).isEqualTo(229000)
        assertThat(item.code).isEqualTo("seqra-item")
        assertThat(item.splitReceiverId).isEqualTo("rcv")
        assertThat(item.tax).isNull()
        assertThat(item.uktzed).isNull()
    }

    @Test
    fun `without a pretty name the plan's own name is used`() {
        assertThat(built(invoice(line(InvoiceItemType.RECURRING, "10", "monitoring-month", null)), 1000).name)
            .isEqualTo("monitoring-month")
    }

    @Test
    fun `credit used and kopecks lower the sum, not the name`() {
        val inv =
            invoice(
                line(InvoiceItemType.RECURRING, "2290.00", "m", "Seqra Monitoring, 1 місяць"),
                line(InvoiceItemType.CBA_ADJ, "-206.55"),
            )
        val item = built(inv, 208345)
        assertThat(item.name).isEqualTo("Seqra Monitoring, 1 місяць")
        assertThat(item.qty).isEqualByComparingTo("1")
        assertThat(item.sum).isEqualTo(208345)
    }

    @Test
    fun `an external charge is named by its cleaned description, else its product`() {
        assertThat(
            built(
                invoice(line(InvoiceItemType.EXTERNAL_CHARGE, "990", description = " Seqra Audit,\n 1\tдомен ")),
                99000,
            ).name,
        ).isEqualTo("Seqra Audit, 1 домен")
        assertThat(
            built(
                invoice(line(InvoiceItemType.EXTERNAL_CHARGE, "990", product = "audit", prettyProduct = "Audit")),
                99000,
            ).name,
        ).isEqualTo("Audit")
    }

    @Test
    fun `several charge lines give one item, distinct names joined in order`() {
        val inv =
            invoice(
                line(InvoiceItemType.RECURRING, "100", "a", "A"),
                line(InvoiceItemType.FIXED, "50", "b", "B"),
                line(InvoiceItemType.RECURRING, "10", "a2", "A"),
                line(InvoiceItemType.EXTERNAL_CHARGE, "5", description = "C"),
            )
        val item = built(inv, 16500)
        assertThat(item.name).isEqualTo("A; B; C")
        assertThat(item.sum).isEqualTo(16500)
    }

    @Test
    fun `a line adjusted to nothing names nothing, and an adjustment of another invoice's item names nothing`() {
        val adjusted = UUID.randomUUID()
        val inv =
            invoice(
                line(InvoiceItemType.RECURRING, "100", "a", "A", id = adjusted),
                line(InvoiceItemType.ITEM_ADJ, "-100", linked = adjusted),
                line(InvoiceItemType.RECURRING, "50", "b", "B"),
                line(InvoiceItemType.REPAIR_ADJ, "-30", linked = UUID.randomUUID()),
                line(InvoiceItemType.USAGE, "0", "c", "C"),
            )
        assertThat(built(inv, 2000).name).isEqualTo("B")
    }

    @Test
    fun `long names are cut to 128 code points`() {
        val long = "😀".repeat(100) + "a".repeat(100)
        val name = built(invoice(line(InvoiceItemType.EXTERNAL_CHARGE, "1", description = long)), 100).name
        assertThat(name.codePointCount(0, name.length)).isEqualTo(128)
        assertThat(name).startsWith("😀".repeat(100))
        assertThat(Baskets.cutName(long)).isEqualTo(name)
    }

    @Test
    fun `what can't be built falls back with a reason`() {
        val ok = line(InvoiceItemType.RECURRING, "10", "a", "A")
        assertThat(fallback(invoice(ok, accountId = UUID.randomUUID()), 1000)).contains("another account")
        assertThat(fallback(invoice(ok, currency = "EUR"), 1000)).contains("currency")
        assertThat(fallback(invoice(line(InvoiceItemType.CBA_ADJ, "10")), 1000)).contains("no charge line")
        assertThat(fallback(invoice(line(InvoiceItemType.TAX, "10", description = "VAT")), 1000))
            .contains("no charge line")
        assertThat(fallback(invoice(line(InvoiceItemType.EXTERNAL_CHARGE, "10", description = " \n ")), 1000))
            .contains("no name")
    }
}
