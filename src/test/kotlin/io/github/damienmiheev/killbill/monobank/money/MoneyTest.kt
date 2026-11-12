/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

class MoneyTest {
    @ParameterizedTest
    @CsvSource(
        "UAH, 2290.00, 229000, 980",
        "UAH, 0.01, 1, 980",
        "USD, 19.99, 1999, 840",
        "EUR, 5, 500, 978",
        "JPY, 1500, 1500, 392",
        "KWD, 1.234, 1234, 414",
        "UAH, 0, 0, 980",
    )
    fun `converts by the currency's own exponent`(
        code: String,
        major: String,
        minor: Long,
        numeric: Int,
    ) {
        val money = Money.ofMajor(BigDecimal(major), Money.currency(code))

        assertThat(money.minor).isEqualTo(minor)
        assertThat(money.numericCode).isEqualTo(numeric)
        assertThat(money.toMajor()).isEqualByComparingTo(major)
    }

    @ParameterizedTest
    @CsvSource("UAH, 1.005", "USD, 0.001", "JPY, 1.5", "KWD, 0.0001")
    fun `refuses more decimals than the currency allows instead of rounding`(
        code: String,
        major: String,
    ) {
        assertThatThrownBy { Money.ofMajor(BigDecimal(major), Money.currency(code)) }
            .isInstanceOf(InvalidAmountException::class.java)
            .hasMessageContaining("more decimals")
    }

    @ParameterizedTest
    @CsvSource("UAH, 12.3400", "JPY, 100.000")
    fun `accepts trailing zeros beyond the exponent`(
        code: String,
        major: String,
    ) {
        assertThat(Money.ofMajor(BigDecimal(major), Money.currency(code)).toMajor()).isEqualByComparingTo(major)
    }

    @org.junit.jupiter.api.Test
    fun `refuses negative and oversized amounts`() {
        val uah = Money.currency("UAH")
        assertThatThrownBy { Money.ofMajor(BigDecimal("-1"), uah) }.isInstanceOf(InvalidAmountException::class.java)
        assertThatThrownBy { Money.ofMajor(BigDecimal("1e30"), uah) }.isInstanceOf(InvalidAmountException::class.java)
    }

    @org.junit.jupiter.api.Test
    fun `compares monobank numeric codes with the expected currency, never by reverse lookup`() {
        assertThat(Money.isSameCurrency(980, Money.currency("UAH"))).isTrue
        assertThat(Money.isSameCurrency(840, Money.currency("UAH"))).isFalse
        assertThatThrownBy { Money.currency("XYZ1") }.isInstanceOf(InvalidAmountException::class.java)
    }

    @org.junit.jupiter.api.Test
    fun `the largest representable amount fits and one more does not`() {
        val uah = Money.currency("UAH")
        assertThat(Money.ofMajor(BigDecimal.valueOf(Long.MAX_VALUE, 2), uah).minor).isEqualTo(Long.MAX_VALUE)
        assertThatThrownBy { Money.ofMajor(BigDecimal.valueOf(Long.MAX_VALUE, 2).add(BigDecimal("0.01")), uah) }
            .isInstanceOf(InvalidAmountException::class.java)
        assertThatThrownBy { Money.ofMajor(BigDecimal("1E+999999999"), uah) }
            .isInstanceOf(InvalidAmountException::class.java)
        assertThatThrownBy { Money(-1, uah) }.isInstanceOf(InvalidAmountException::class.java)
    }

    @org.junit.jupiter.api.Test
    fun `refuses currencies without a minor unit`() {
        // XAU (gold) has no defined minor unit.
        assertThatThrownBy { Money.ofMajor(BigDecimal.ONE, Money.currency("XAU")) }
            .isInstanceOf(InvalidAmountException::class.java)
    }
}
