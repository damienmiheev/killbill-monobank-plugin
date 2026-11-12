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
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import java.math.BigDecimal

/**
 * `merchantPaymInfo.basketOrder`: what monobank prints on its fiscal receipt and how split payments are divided.
 * The caller may pass its own basket as JSON (property `basketOrder`); otherwise, when the tenant sets
 * `basketItemCode` or `splitReceiverId`, the plugin sends one item priced at one currency unit, with the amount as
 * its quantity (22.90 × 1.00).
 *
 * monobank lets a partial capture or refund change only the quantities of the charge's own items (an item with
 * another name, code, unit price or split receiver is refused, seen live: 1001 "finalization basket contains item
 * not found in hold"). Pricing the default item at one unit makes every partial amount an exact quantity.
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
                .builder(name.take(MAX_NAME), quantityOf(amountMinor), UNIT_MINOR, code)
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

    /**
     * The basket as stored on a row, to rebuild the items of later captures and refunds: the items, and whether they
     * are the plugin's default one-unit item ([single]) or the caller's own.
     */
    fun toJson(
        items: List<BasketItem>?,
        isDefault: Boolean,
    ): String? =
        items?.let { list ->
            JSON.writeValueAsString(
                mapOf(
                    "default" to isDefault,
                    "items" to
                        list.map { item ->
                            linkedMapOf<String, Any?>(
                                "name" to item.name,
                                "qty" to item.qty,
                                "sum" to item.sum,
                                "code" to item.code,
                                "splitReceiverId" to item.splitReceiverId,
                                "unit" to item.unit,
                                "tax" to item.tax,
                                "uktzed" to item.uktzed,
                            ).filterValues { it != null }
                        },
                ),
            )
        }

    /** A capture's or refund's items with the charge's own default/caller marker, for storing on its row. */
    fun storedFor(
        items: List<BasketItem>?,
        chargeBasket: String?,
    ): String? = toJson(items, isDefault = chargeBasket?.let { JSON.readTree(it).path("default").asBoolean() } == true)

    /**
     * The receipt items of a capture or refund of [amountMinor] (`invoice/finalize`, `invoice/cancel`), built from
     * the basket of the charge it acts on ([chargeBasket], null when the charge sent none). monobank accepts only
     * the charge's own items (same name, code, unit price and split receiver) with quantities no larger:
     * - the caller's `basketOrder` for this operation, when given: checked item by item against the charge's;
     * - else the charge's default item with the amount as its quantity;
     * - else the charge's own items, unchanged, when the operation is for their whole total.
     * A partial operation on a charge with the caller's own items needs the caller's `basketOrder`: only the caller
     * knows which quantities to give back. Throws [IllegalArgumentException] with the reason otherwise.
     */
    fun forOperation(
        json: String?,
        chargeBasket: String?,
        amountMinor: Long,
    ): List<BasketItem>? {
        val stored = stored(chargeBasket)
        val given = fromProperty(json, amountMinor)
        if (given != null) {
            require(stored != null) { "the payment was charged without receipt items, so basketOrder cannot be used" }
            given.forEach { item ->
                val match = stored.items.firstOrNull { key(it) == key(item) }
                require(match != null) {
                    "basketOrder item '${item.name}' (${item.code}, ${item.sum}) is not in the payment's receipt"
                }
                require(item.qty <= match.qty) { "basketOrder item '${item.name}' has more than the payment had" }
            }
            return given
        }
        if (stored == null) return null
        return when {
            stored.isDefault -> listOf(copy(stored.items.single(), quantityOf(amountMinor)))

            stored.items
                .fold(BigDecimal.ZERO) { acc, item -> acc + item.qty * BigDecimal.valueOf(item.sum) }
                .compareTo(BigDecimal.valueOf(amountMinor)) == 0 -> stored.items

            else -> throw IllegalArgumentException(
                "a partial capture or refund of a payment with its own basketOrder needs a basketOrder of " +
                    "the same items with smaller quantities (monobank changes only quantities)",
            )
        }
    }

    /** The SDK's receipt items of a capture or refund. */
    fun fiscal(items: List<BasketItem>): List<FiscalizationItem> =
        items.map { item ->
            FiscalizationItem
                .builder(item.name, item.qty, item.sum, item.code)
                .splitReceiverId(item.splitReceiverId)
                .tax(item.tax)
                .uktzed(item.uktzed)
                .build()
        }

    private class Stored(
        val isDefault: Boolean,
        val items: List<BasketItem>,
    )

    private fun stored(json: String?): Stored? {
        if (json == null) return null
        val node =
            try {
                JSON.readTree(json)
            } catch (e: com.fasterxml.jackson.core.JacksonException) {
                throw IllegalArgumentException("the payment's stored receipt cannot be read", e)
            }
        return Stored(node.path("default").asBoolean(), node.path("items").map(::item))
    }

    /** What monobank matches a capture's or refund's item on. */
    private fun key(item: BasketItem): List<Any?> =
        listOf(item.name.take(MAX_NAME), item.code, item.sum, item.splitReceiverId)

    private fun copy(
        item: BasketItem,
        qty: BigDecimal,
    ): BasketItem =
        BasketItem
            .builder(item.name, qty, item.sum, item.code)
            .total(qty.multiply(BigDecimal.valueOf(item.sum)).longValueExact())
            .splitReceiverId(item.splitReceiverId)
            .tax(item.tax)
            .uktzed(item.uktzed)
            .unit(item.unit)
            .build()

    /** The quantity of one-unit items that makes [amountMinor] (2290 → 22.90). */
    private fun quantityOf(amountMinor: Long): BigDecimal = BigDecimal.valueOf(amountMinor, UNIT_SCALE)

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

    // UAH (the only currency the plugin takes) has two decimals: one unit is 100 minor units.
    private const val UNIT_SCALE = 2
    private const val UNIT_MINOR = 100L
}
