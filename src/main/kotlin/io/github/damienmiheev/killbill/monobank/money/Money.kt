/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.money

import java.math.BigDecimal
import java.util.Currency

/**
 * An amount in the minor unit of its currency (kopiyky, cents, yen, fils).
 *
 * The one place where Kill Bill's `BigDecimal` major-unit amounts and monobank's integer minor-unit
 * amounts meet. The number of decimals comes from the currency's ISO 4217 exponent, never an assumed 2.
 */
data class Money(
    val minor: Long,
    val currency: Currency,
) {
    init {
        if (minor < 0) throw InvalidAmountException("amount must not be negative")
    }

    /** ISO 4217 numeric code, as monobank's `ccy` expects. */
    val numericCode: Int get() = currency.numericCode

    fun toMajor(): BigDecimal = BigDecimal.valueOf(minor, exponentOf(currency))

    companion object {
        /** Long holds 19 digits; more integer digits can never fit after scaling. */
        private const val MAX_INTEGER_DIGITS = 19

        /**
         * Converts a Kill Bill amount. Fails with [InvalidAmountException] if the amount is negative,
         * has more decimals than the currency allows (never rounded) or does not fit in a Long.
         */
        fun ofMajor(
            amount: BigDecimal,
            currency: Currency,
        ): Money {
            val exponent = exponentOf(currency)
            check(amount.signum() >= 0) { "amount must not be negative" }
            // Bound the size before scaling, so an absurd exponent cannot allocate a huge number.
            check(amount.precision() - amount.scale() <= MAX_INTEGER_DIGITS) { "amount $amount is too large" }
            val minor =
                runCatching { amount.movePointRight(exponent).stripTrailingZeros().toBigIntegerExact() }
                    .getOrElse {
                        throw InvalidAmountException(
                            "amount $amount has more decimals than ${currency.currencyCode} allows ($exponent)",
                        )
                    }
            check(minor.bitLength() < Long.SIZE_BITS) { "amount $amount is too large" }
            return Money(minor.toLong(), currency)
        }

        private inline fun check(
            condition: Boolean,
            message: () -> String,
        ) {
            if (!condition) throw InvalidAmountException(message())
        }

        /** Looks up a currency by its ISO 4217 alpha code, e.g. "UAH". */
        fun currency(alphaCode: String): Currency =
            try {
                Currency.getInstance(alphaCode.trim().uppercase())
            } catch (_: IllegalArgumentException) {
                throw InvalidAmountException("unknown currency $alphaCode")
            }

        /**
         * Whether monobank's numeric `ccy` denotes [currency]. Compare this way, never by a reverse lookup:
         * some numeric codes belong to more than one ISO 4217 entry.
         */
        fun isSameCurrency(
            numericCode: Int,
            currency: Currency,
        ): Boolean = currency.numericCode == numericCode

        private fun exponentOf(currency: Currency): Int {
            val digits = currency.defaultFractionDigits
            if (digits < 0) throw InvalidAmountException("${currency.currencyCode} has no minor unit")
            return digits
        }
    }
}

class InvalidAmountException(
    message: String,
) : IllegalArgumentException(message)
