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
                isDefault = true,
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
                isDefault = false,
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
    fun `a caller's single item gives back a fraction of itself, never more than it had`() {
        val coffee =
            Baskets.toJson(
                Baskets.fromProperty("""[{"name":"Coffee","qty":3,"sum":100,"code":"coffee"}]""", 300),
                isDefault = false,
            )
        assertThat(Baskets.forOperation(null, coffee, 150)!!.single().qty).isEqualByComparingTo("1.5")
        assertThat(Baskets.forOperation(null, coffee, 300)!!.single().qty).isEqualByComparingTo("3")
        assertThatThrownBy { Baskets.forOperation(null, coffee, 301) }.hasMessageContaining("more than the payment had")
    }

    @Test
    fun `a one-item charge at its price gives back amount over price, in steps of three decimals`() {
        val plan =
            Baskets.toJson(
                Baskets.fromProperty(
                    """[{"name":"Plan","qty":1,"sum":229000,"code":"p","splitReceiverId":"r"}]""",
                    229000,
                ),
                isDefault = false,
            )
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
    }

    @Test
    fun `the amount step of a stored basket`() {
        fun one(sum: Long) =
            Baskets.toJson(
                Baskets.fromProperty("""[{"name":"P","qty":1,"sum":$sum,"code":"p"}]""", sum),
                isDefault = false,
            )
        val default =
            Baskets.toJson(
                Baskets.single(settings("org.killbill.billing.plugin.monobank.basketItemCode=x"), "P", 208345),
                isDefault = true,
            )
        assertThat(Baskets.amountStep(default)).isEqualTo(1)
        assertThat(Baskets.amountStep(one(229000))).isEqualTo(229)
        assertThat(Baskets.amountStep(one(10000))).isEqualTo(10)
        assertThat(Baskets.amountStep(one(208345))).isEqualTo(41669)
        val two =
            Baskets.toJson(
                Baskets.fromProperty(
                    """[{"name":"A","qty":1,"sum":100,"code":"a"},{"name":"B","qty":1,"sum":100,"code":"b"}]""",
                    200,
                ),
                isDefault = false,
            )
        assertThat(Baskets.amountStep(two)).isNull()
        assertThat(Baskets.amountStep(null)).isNull()
        assertThat(Baskets.amountStep("not json")).isNull()
    }

    @Test
    fun `a row stored by 0_1_4 is captured and refunded in part`() {
        // the exact JSON 0.1.4 wrote: the default item, and seqra-be's own item
        val defaultRow =
            """{"default":true,"items":[{"name":"Payment","qty":2290.00,"sum":100,"code":"payment",""" +
                """"splitReceiverId":"r"}]}"""
        val ownRow =
            """{"default":false,"items":[{"name":"Seqra Monitoring, 1 місяць","qty":1,"sum":229000,"code":"c",""" +
                """"splitReceiverId":"r"}]}"""
        assertThat(Baskets.forOperation(null, defaultRow, 150)!!.single().qty).isEqualByComparingTo("1.50")
        assertThat(Baskets.forOperation(null, ownRow, 114500)!!.single().qty).isEqualByComparingTo("0.5")
        assertThat(Baskets.amountStep(ownRow)).isEqualTo(229)
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
