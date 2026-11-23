/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import io.github.damienmiheev.killbill.monobank.config.TenantSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BasketsTest {
    private fun settings(vararg extra: String): TenantSettings {
        val raw =
            (
                listOf(
                    "org.killbill.billing.plugin.monobank.merchantToken=t",
                    "org.killbill.billing.plugin.monobank.webhookUrl=https://example.com/h",
                ) + extra
            ).joinToString("\n")
        return (TenantSettings.parse(raw, null) { null } as TenantSettings.ParseResult.Valid).settings
    }

    @Test
    fun `no basket unless the tenant asks for one`() {
        assertThat(Baskets.single(settings(), "Plan", 229000)).isNull()
    }

    @Test
    fun `a split receiver gives one item priced at one unit, the amount as its quantity`() {
        val items =
            Baskets.single(
                settings("org.killbill.billing.plugin.monobank.splitReceiverId=rcv-1"),
                "Plan",
                229000,
            )!!
        assertThat(items).hasSize(1)
        assertThat(items[0].sum).isEqualTo(100)
        assertThat(items[0].qty).isEqualByComparingTo("2290.00")
        assertThat(items[0].total).isEqualTo(229000)
        assertThat(items[0].splitReceiverId).isEqualTo("rcv-1")
        assertThat(items[0].code).isEqualTo("payment")
    }

    @Test
    fun `the caller's basket must add up to the amount`() {
        val json =
            """[{"name":"Audit","qty":1,"sum":1000000,"code":"audit"},""" +
                """{"name":"Fee","qty":2,"sum":145000,"code":"fee"}]"""
        assertThat(Baskets.fromProperty(json, 1290000)!!.map { it.code }).containsExactly("audit", "fee")
        assertThatThrownBy { Baskets.fromProperty(json, 1) }.hasMessageContaining("adds up to")
        assertThatThrownBy { Baskets.fromProperty("{}", 1) }.hasMessageContaining("non-empty JSON array")
        assertThatThrownBy {
            Baskets.fromProperty("""[{"name":"","sum":1,"code":"x"}]""", 1)
        }.hasMessageContaining("needs name")
    }

    @Test
    fun `a capture or refund repeats the charge's default item with the amount as its quantity`() {
        val charge =
            Baskets.toJson(
                Baskets.single(settings("org.killbill.billing.plugin.monobank.splitReceiverId=rcv-1"), "Plan", 200),
                Baskets.Kind.DEFAULT,
            )
        assertThat(Baskets.forOperation(null, null, 150)).isNull()

        val partial = Baskets.forOperation(null, charge, 150)!!.single()
        assertThat(partial.name).isEqualTo("Plan")
        assertThat(partial.code).isEqualTo("payment")
        assertThat(partial.sum).isEqualTo(100) // the hold's unit price: monobank matches name, code, sum, receiver
        assertThat(partial.qty).isEqualByComparingTo("1.50")
        assertThat(partial.splitReceiverId).isEqualTo("rcv-1")
        assertThat(Baskets.forOperation(null, charge, 1)!!.single().qty).isEqualByComparingTo("0.01")
    }

    @Test
    fun `a charge with the caller's own items is repeated in full, and a partial one needs the caller's basket`() {
        val own =
            Baskets.toJson(
                Baskets.fromProperty(
                    """[{"name":"Audit","qty":1,"sum":1000,"code":"audit"},""" +
                        """{"name":"Fee","qty":2,"sum":145,"code":"fee"}]""",
                    1290,
                ),
                Baskets.Kind.CALLER,
            )
        assertThat(Baskets.forOperation(null, own, 1290)!!.map { it.code to it.qty.toInt() })
            .containsExactly("audit" to 1, "fee" to 2)
        assertThatThrownBy { Baskets.forOperation(null, own, 145) }.hasMessageContaining("needs a basketOrder")
        val feeOnly = """[{"name":"Fee","qty":1,"sum":145,"code":"fee"}]"""
        assertThat(Baskets.forOperation(feeOnly, own, 145)!!.single().code).isEqualTo("fee")

        // a caller basket is checked against the charge's items before anything is sent
        val stranger = """[{"name":"Tea","qty":1,"sum":145,"code":"tea"}]"""
        assertThatThrownBy { Baskets.forOperation(stranger, own, 145) }.hasMessageContaining("is not in the payment")
        val tooMany = """[{"name":"Fee","qty":3,"sum":145,"code":"fee"}]"""
        assertThatThrownBy { Baskets.forOperation(tooMany, own, 435) }.hasMessageContaining("more than the payment had")
        assertThatThrownBy { Baskets.forOperation(feeOnly, null, 145) }.hasMessageContaining("without receipt items")
    }

    @Test
    fun `a caller's single item is repeated in full, and a part of it needs the caller's basket, as before`() {
        val coffee =
            Baskets.toJson(
                Baskets.fromProperty("""[{"name":"Coffee","qty":1.2345,"sum":2000,"code":"coffee"}]""", 2469),
                Baskets.Kind.CALLER,
            )
        assertThat(Baskets.forOperation(null, coffee, 2469)!!.single().qty).isEqualByComparingTo("1.2345")
        assertThatThrownBy { Baskets.forOperation(null, coffee, 1000) }.hasMessageContaining("needs a basketOrder")
        assertThat(Baskets.amountStep(coffee)).isNull()
    }

    private fun invoiceItem(sum: Long) =
        Baskets.toJson(
            listOf(
                io.github.damienmiheev.monobank.acquiring.common.BasketItem
                    .builder("Plan", java.math.BigDecimal.ONE, sum, "p")
                    .total(sum)
                    .splitReceiverId("r")
                    .build(),
            ),
            Baskets.Kind.INVOICE,
        )

    @Test
    fun `an invoice's item gives back amount over price, in steps of three decimals`() {
        val plan = invoiceItem(229000)
        val tenth = Baskets.forOperation(null, plan, 22900)!!.single()
        assertThat(tenth.qty).isEqualByComparingTo("0.1")
        assertThat(tenth.sum).isEqualTo(229000)
        assertThat(tenth.splitReceiverId).isEqualTo("r")
        assertThat(Baskets.forOperation(null, plan, 229)!!.single().qty).isEqualByComparingTo("0.001")
        assertThat(Baskets.forOperation(null, plan, 229000)!!.single().qty).isEqualByComparingTo("1")
        assertThatThrownBy { Baskets.forOperation(null, plan, 228) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("amount step 229")
        assertThatThrownBy {
            Baskets.forOperation(
                null,
                plan,
                229229,
            )
        }.hasMessageContaining("more than the payment had")
        // a refund after a part capture works on the capture's item, still an invoice's item
        val captured = Baskets.storedFor(Baskets.forOperation(null, plan, 114500), plan)
        assertThat(Baskets.forOperation(null, captured, 22900)!!.single().qty).isEqualByComparingTo("0.1")
        assertThat(Baskets.amountStep(captured)).isEqualTo(229)
        // kopecks: whole amounts always fit, parts only in the item's large step
        val odd = invoiceItem(208345)
        assertThat(Baskets.forOperation(null, odd, 208345)!!.single().qty).isEqualByComparingTo("1")
        assertThat(Baskets.forOperation(null, odd, 41669)!!.single().qty).isEqualByComparingTo("0.2")
    }

    @Test
    fun `the amount step of a stored basket`() {
        val default =
            Baskets.toJson(
                Baskets.single(settings("org.killbill.billing.plugin.monobank.basketItemCode=x"), "P", 208345),
                Baskets.Kind.DEFAULT,
            )
        assertThat(Baskets.amountStep(default)).isEqualTo(1)
        assertThat(Baskets.amountStep(invoiceItem(229000))).isEqualTo(229)
        assertThat(Baskets.amountStep(invoiceItem(10000))).isEqualTo(10)
        assertThat(Baskets.amountStep(invoiceItem(208345))).isEqualTo(41669)
        assertThat(Baskets.amountStep(null)).isNull()
        assertThat(Baskets.amountStep("not json")).isNull()
    }

    @Test
    fun `rows stored by 0_1_4 work as before`() {
        // the exact JSON 0.1.4 wrote: the default item, and seqra-be's own item
        val defaultRow =
            """{"default":true,"items":[{"name":"Payment","qty":2290.00,"sum":100,"code":"payment",""" +
                """"splitReceiverId":"r"}]}"""
        val ownRow =
            """{"default":false,"items":[{"name":"Seqra Monitoring, 1 місяць","qty":1,"sum":229000,"code":"c",""" +
                """"splitReceiverId":"r"}]}"""
        assertThat(Baskets.forOperation(null, defaultRow, 150)!!.single().qty).isEqualByComparingTo("1.50")
        assertThat(Baskets.amountStep(defaultRow)).isEqualTo(1)
        assertThat(Baskets.forOperation(null, ownRow, 229000)!!.single().qty).isEqualByComparingTo("1")
        assertThatThrownBy { Baskets.forOperation(null, ownRow, 114500) }.hasMessageContaining("needs a basketOrder")
        assertThat(Baskets.amountStep(ownRow)).isNull()
    }

    @Test
    fun `fractional quantities add up exactly, and a fractional sum is refused`() {
        val json = """[{"name":"Hours","qty":0.1,"sum":1000,"code":"h"},{"name":"Kg","qty":2.5,"sum":30,"code":"kg"}]"""
        assertThat(Baskets.fromProperty(json, 175)).hasSize(2)
        assertThatThrownBy { Baskets.fromProperty(json, 174) }.hasMessageContaining("adds up to")
        assertThatThrownBy { Baskets.fromProperty("""[{"name":"A","qty":1,"sum":10.5,"code":"a"}]""", 10) }
            .hasMessageContaining("needs name")
    }
}
