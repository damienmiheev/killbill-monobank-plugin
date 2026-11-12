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
    fun `a split receiver gives a one-item basket for the whole amount`() {
        val items =
            Baskets.single(
                settings("org.killbill.billing.plugin.monobank.splitReceiverId=rcv-1"),
                "Plan",
                229000,
            )!!
        assertThat(items).hasSize(1)
        assertThat(items[0].sum).isEqualTo(229000)
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
    fun `captures and refunds carry no items when the tenant uses no receipts, one item when it does`() {
        assertThat(Baskets.forOperation(settings(), "Plan", null, 1500)).isNull()
        val items =
            Baskets.forOperation(
                settings("org.killbill.billing.plugin.monobank.splitReceiverId=rcv-1"),
                "Plan",
                null,
                1500,
            )!!
        assertThat(
            items.map { Triple(it.sum, it.qty.toInt(), it.splitReceiverId) },
        ).containsExactly(Triple(1500L, 1, "rcv-1"))
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
