/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.damienmiheev.killbill.monobank.config.TenantSettings
import io.github.damienmiheev.killbill.monobank.money.Money
import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import org.killbill.billing.invoice.api.InvoiceItemType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * `merchantPaymInfo.basketOrder`: what monobank prints on its fiscal receipt and how split payments are divided.
 * The caller may pass its own basket as JSON (property `basketOrder`); otherwise, when the tenant sets
 * `basketItemCode` or `splitReceiverId`, the plugin sends one item: built from the Kill Bill invoice the payment pays
 * (`basketFromInvoice`, [fromInvoice]: "Seqra Monitoring" × 1 at the amount), or priced at one currency unit with
 * the amount as its quantity (22.90 × 1.00, [single]).
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
                .builder(cutName(name), quantityOf(amountMinor), UNIT_MINOR, code)
                .total(amountMinor)
                .splitReceiverId(settings.splitReceiverId)
                .build(),
        )
    }

    /**
     * The one item of a charge that pays [invoice] (design A5, "Receipt items from the invoice"): named after what the
     * invoice charges, quantity 1, priced at the charge's amount, with the tenant's code and split receiver. A
     * [InvoiceBasket.Fallback] says why it can't be built (the caller then sends the default item).
     */
    fun fromInvoice(
        invoice: InvoiceView,
        settings: TenantSettings,
        accountId: java.util.UUID,
        money: Money,
    ): InvoiceBasket {
        val code = settings.basketItemCode ?: return InvoiceBasket.Fallback("the tenant sends no basket")
        if (invoice.accountId != accountId) return InvoiceBasket.Fallback("the invoice belongs to another account")
        if (invoice.currency != money.currency.currencyCode) {
            return InvoiceBasket.Fallback("the invoice's currency is ${invoice.currency}")
        }
        if (money.minor <= 0) return InvoiceBasket.Fallback("nothing to charge")
        val onInvoice = invoice.items.map { it.id }.toSet()
        // adjustments on this invoice of its own items; one linked to another invoice's item names nothing
        val adjusted =
            invoice.items
                .filter { it.type in ADJUSTMENTS && it.linkedItemId != null && it.linkedItemId in onInvoice }
                .groupBy({ it.linkedItemId!! }, { it.amount })
                .mapValues { (_, amounts) -> amounts.fold(BigDecimal.ZERO, BigDecimal::add) }
        val lines =
            invoice.items.filter { line ->
                line.type in CHARGES && (line.amount + (adjusted[line.id] ?: BigDecimal.ZERO)).signum() > 0
            }
        if (lines.isEmpty()) return InvoiceBasket.Fallback("the invoice has no charge line")
        val names = lines.mapNotNull { clean(nameOf(it)) }.distinct()
        if (names.isEmpty()) return InvoiceBasket.Fallback("the invoice's charge lines have no name")
        return InvoiceBasket.Built(
            BasketItem
                .builder(cutName(names.joinToString("; ")), BigDecimal.ONE, money.minor, code)
                .total(money.minor)
                .splitReceiverId(settings.splitReceiverId)
                .build(),
        )
    }

    private fun nameOf(line: InvoiceView.Line): String? =
        if (line.type == InvoiceItemType.EXTERNAL_CHARGE) {
            line.description?.takeIf { it.isNotBlank() } ?: line.prettyProductName ?: line.productName
        } else {
            line.prettyPlanName?.takeIf { it.isNotBlank() } ?: line.planName
        }

    /** Control characters and line breaks become spaces, runs of spaces one, the ends trimmed; null when empty. */
    private fun clean(name: String?): String? =
        name
            ?.replace(CONTROL, " ")
            ?.replace(SPACES, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** A name cut to monobank's 128 characters, by code points: the one rule for every name the plugin cuts. */
    fun cutName(name: String): String =
        if (name.codePointCount(0, name.length) <= MAX_NAME) {
            name
        } else {
            name.substring(0, name.offsetByCodePoints(0, MAX_NAME))
        }

    /**
     * The amount step of a charge stored with [json] (minor units): a part capture or refund of it is a multiple of
     * this. Only for a one-item basket (`sum ÷ gcd(sum, 1000)`, 1 for the default item); null otherwise.
     */
    fun amountStep(json: String?): Long? =
        runCatching { stored(json) }
            .getOrNull()
            ?.items
            ?.singleOrNull()
            ?.let { step(it.sum) }

    private fun step(sum: Long): Long = sum / gcd(sum, QTY_SCALE)

    private tailrec fun gcd(
        a: Long,
        b: Long,
    ): Long = if (b == 0L) a else gcd(b, a % b)

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
     * - else, for a charge with one item, that item with quantity = amount ÷ its price (at most 3 decimals, no more
     *   than the item had: the amount is a multiple of [amountStep]);
     * - else the charge's own items, unchanged, when the operation is for their whole total.
     * A partial operation on a charge with several items of the caller's needs the caller's `basketOrder`: only the
     * caller knows which quantities to give back. Throws [IllegalArgumentException] with the reason otherwise.
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
        // the default item exactly as before (22.90 × 1.00: two decimals, as seen live); any other single item by
        // its price
        if (stored.isDefault) return listOf(copy(stored.items.single(), quantityOf(amountMinor)))
        stored.items.singleOrNull()?.let { item -> return listOf(copy(item, quantityFor(item, amountMinor))) }
        return when {
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
        listOf(cutName(item.name), item.code, item.sum, item.splitReceiverId)

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

    /** The quantity of [item] that makes [amountMinor]: exact, at most 3 decimals, no more than the item had. */
    private fun quantityFor(
        item: BasketItem,
        amountMinor: Long,
    ): BigDecimal {
        val qty =
            try {
                BigDecimal
                    .valueOf(amountMinor)
                    .divide(BigDecimal.valueOf(item.sum), QTY_DECIMALS, RoundingMode.UNNECESSARY)
                    .stripTrailingZeros()
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException(
                    "the amount $amountMinor is not a multiple of this payment's amount step ${step(item.sum)} " +
                        "(its receipt item is priced ${item.sum}; monobank changes only the item's quantity)",
                )
            }
        require(qty <= item.qty) { "the amount $amountMinor is more than the payment had (${item.qty} × ${item.sum})" }
        return qty
    }

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
        val builder = BasketItem.builder(cutName(name), qty, sum, code)
        node.path("splitReceiverId").takeIf { it.isTextual }?.let { builder.splitReceiverId(it.asText()) }
        node.path("unit").takeIf { it.isTextual }?.let { builder.unit(it.asText()) }
        node.path("uktzed").takeIf { it.isTextual }?.let { builder.uktzed(it.asText()) }
        node.path("tax").takeIf { it.isArray }?.let { taxes -> builder.tax(taxes.map { it.asInt() }) }
        return builder.build()
    }

    private const val MAX_NAME = 128

    // A capture's or refund's quantity has at most 3 decimals (more is untested live).
    private const val QTY_DECIMALS = 3
    private const val QTY_SCALE = 1000L

    private val CHARGES =
        setOf(
            InvoiceItemType.RECURRING,
            InvoiceItemType.FIXED,
            InvoiceItemType.USAGE,
            InvoiceItemType.EXTERNAL_CHARGE,
        )
    private val ADJUSTMENTS = setOf(InvoiceItemType.ITEM_ADJ, InvoiceItemType.REPAIR_ADJ)
    private val CONTROL = Regex("[\\p{Cntrl}\\p{Zl}\\p{Zp}]")
    private val SPACES = Regex("\\s+")

    // UAH (the only currency the plugin takes) has two decimals: one unit is 100 minor units.
    private const val UNIT_SCALE = 2
    private const val UNIT_MINOR = 100L
}
