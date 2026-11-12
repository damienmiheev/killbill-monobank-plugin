/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.damienmiheev.killbill.monobank.config.TenantSettings
import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import java.math.BigDecimal

/**
 * `merchantPaymInfo.basketOrder`: what monobank prints on its fiscal receipt and how split payments are divided.
 * The caller may pass its own basket as JSON (property `basketOrder`); otherwise, when the tenant sets
 * `basketItemCode` or `splitReceiverId`, the plugin sends one item for the whole amount.
 */
object Baskets {
    // Exact decimals: a quantity of 0.1 must stay 0.1, not the nearest double.
    private val JSON = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    /** The tenant's one-item basket, or null when the tenant does not ask for baskets. */
    fun single(
        settings: TenantSettings,
        name: String,
        amountMinor: Long,
    ): List<BasketItem>? {
        val code = settings.basketItemCode ?: return null
        return listOf(
            BasketItem
                .builder(name.take(MAX_NAME), BigDecimal.ONE, amountMinor, code)
                .total(amountMinor)
                .splitReceiverId(settings.splitReceiverId)
                .build(),
        )
    }

    /**
     * Parses the caller's basket: a JSON array of `{name, qty, sum, code}` with optional `splitReceiverId`, `unit`,
     * `tax` (array of ids) and `uktzed`. The item totals (`sum × qty`) must add up to the amount.
     */
    fun fromProperty(
        json: String?,
        amountMinor: Long,
    ): List<BasketItem>? {
        if (json == null) return null
        val nodes = runCatching { JSON.readTree(json) }.getOrNull()
        require(nodes != null && nodes.isArray && nodes.size() > 0) { "basketOrder must be a non-empty JSON array" }
        val items = nodes.map(::item)
        val total = items.fold(BigDecimal.ZERO) { acc, item -> acc + item.qty * BigDecimal.valueOf(item.sum) }
        require(total.compareTo(BigDecimal.valueOf(amountMinor)) == 0) {
            "basketOrder adds up to ${total.toPlainString()}, not $amountMinor"
        }
        return items
    }

    private fun item(node: JsonNode): BasketItem {
        val name = node.path("name").asText("")
        val code = node.path("code").asText("")
        val sum = node.path("sum").takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong() ?: -1
        val qty = node.path("qty").takeIf { it.isNumber }?.decimalValue() ?: BigDecimal.ONE
        require(name.isNotBlank() && code.isNotBlank() && sum > 0 && qty.signum() > 0) {
            "each basketOrder item needs name, code, sum > 0 and qty > 0"
        }
        val builder = BasketItem.builder(name.take(MAX_NAME), qty, sum, code)
        node.path("splitReceiverId").takeIf { it.isTextual }?.let { builder.splitReceiverId(it.asText()) }
        node.path("unit").takeIf { it.isTextual }?.let { builder.unit(it.asText()) }
        node.path("uktzed").takeIf { it.isTextual }?.let { builder.uktzed(it.asText()) }
        node.path("tax").takeIf { it.isArray }?.let { taxes -> builder.tax(taxes.map { it.asInt() }) }
        return builder.build()
    }

    private const val MAX_NAME = 128
}
