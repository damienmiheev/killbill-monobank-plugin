/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import java.math.BigDecimal
import java.math.RoundingMode

/** Receipt item names: the one cut every name gets, and the cleaning of names the plugin takes from Kill Bill. */
object ItemNames {
    private const val MAX = 128

    // control, line and paragraph separators, and format characters (bidi overrides, zero-width) but the emoji joiner
    private val CONTROL = Regex("[\\p{Cntrl}\\p{Zl}\\p{Zp}\\p{Cf}&&[^\\u200D]]")
    private val SPACES = Regex("\\s+")

    /**
     * A name cut to monobank's 128 characters: 128 UTF-16 units as before 0.1.5 (never more code points, whichever
     * monobank counts), one less when that would split a surrogate pair.
     */
    fun cut(name: String): String {
        if (name.length <= MAX) return name
        val end = if (Character.isHighSurrogate(name[MAX - 1])) MAX - 1 else MAX
        return name.substring(0, end)
    }

    /**
     * Control and format characters and line breaks become spaces, runs of spaces one, the ends trimmed; null when
     * nothing is left. For names from Kill Bill; a caller's own names go as the caller sent them.
     */
    fun clean(name: String?): String? =
        name
            ?.replace(CONTROL, " ")
            ?.replace(SPACES, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}

/** Receipt item quantities: monobank changes only an item's quantity on a capture or refund. */
object Quantities {
    // UAH (the only currency the plugin takes) has two decimals: one unit is 100 minor units.
    private const val UNIT_SCALE = 2
    const val UNIT_MINOR = 100L

    // A capture's or refund's quantity of an invoice's item has at most 3 decimals (more is untested live).
    private const val DECIMALS = 3
    private const val SCALE = 1000L

    /** The quantity of one-unit items that makes [amountMinor] (2290 → 22.90). */
    fun ofUnits(amountMinor: Long): BigDecimal = BigDecimal.valueOf(amountMinor, UNIT_SCALE)

    /** The amount step of an item priced [sum]: `sum ÷ gcd(sum, 1000)` minor units. */
    fun step(sum: Long): Long = sum / gcd(sum, SCALE)

    /** The quantity of [item] that makes [amountMinor]: exact, at most 3 decimals, no more than the item had. */
    fun of(
        item: BasketItem,
        amountMinor: Long,
    ): BigDecimal {
        val qty =
            try {
                BigDecimal
                    .valueOf(amountMinor)
                    .divide(BigDecimal.valueOf(item.sum), DECIMALS, RoundingMode.UNNECESSARY)
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

    private tailrec fun gcd(
        a: Long,
        b: Long,
    ): Long = if (b == 0L) a else gcd(b, a % b)
}
