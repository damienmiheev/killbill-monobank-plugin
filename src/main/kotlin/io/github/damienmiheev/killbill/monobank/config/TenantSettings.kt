/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.config

import io.github.damienmiheev.killbill.monobank.money.InvalidAmountException
import io.github.damienmiheev.killbill.monobank.money.Money
import java.io.StringReader
import java.net.URI
import java.util.Currency
import java.util.Properties

/**
 * One tenant's settings, uploaded with Kill Bill's `uploadPluginConfig` as Java properties
 * under the prefix [PREFIX]. See the README for every key.
 */
class TenantSettings private constructor(
    /** monobank's `X-Token`. Never logged, never returned. */
    val merchantToken: Secret,
    val webhookUrl: URI,
    val redirectUrl: URI?,
    val destination: String?,
    val language: String,
    val allowedCurrencies: Set<Currency>,
    val checkoutValiditySeconds: Long,
    /**
     * When set, every monobank payment carries a one-item basket (`merchantPaymInfo.basketOrder`) with this product
     * code, unless the caller passes its own basket. Merchants with fiscalisation or split payments need it.
     */
    val basketItemCode: String?,
    /** The split receiver every basket item goes to (merchants with split payments). */
    val splitReceiverId: String?,
) {
    override fun toString(): String =
        "TenantSettings(webhookUrl=$webhookUrl, redirectUrl=$redirectUrl, language=$language, " +
            "allowedCurrencies=${allowedCurrencies.map { it.currencyCode }}, " +
            "checkoutValiditySeconds=$checkoutValiditySeconds, merchantToken=$merchantToken)"

    companion object {
        const val PREFIX = "org.killbill.billing.plugin.monobank."

        /**
         * The only environment variables a tenant may point `merchantToken` at: `env:<alias>` reads
         * `MONOBANK_MERCHANT_TOKEN_<ALIAS>`, and only when the operator bound that alias to this tenant
         * (system setting `merchantTokenBindings`), so one tenant can never use another's merchant token.
         */
        const val MERCHANT_TOKEN_ENV_PREFIX = "MONOBANK_MERCHANT_TOKEN_"

        /** The only currencies monobank acquiring accepts (checked live: any other ccy gets 1001 "invalid 'ccy'"). */
        val MONOBANK_CURRENCIES: List<String> = listOf("UAH", "USD", "EUR")

        private val LOG = org.slf4j.LoggerFactory.getLogger(TenantSettings::class.java)
        val ALIAS = Regex("[A-Za-z0-9_]{1,64}")
        private val LANGUAGES = setOf("uk", "en")
        private const val DEFAULT_VALIDITY_SECONDS = 3600L
        private const val DEFAULT_ITEM_CODE = "payment"
        private const val MIN_VALIDITY_SECONDS = 60L
        private const val MAX_VALIDITY_SECONDS = 30L * 24 * 3600

        /**
         * Parses the raw text Kill Bill stores for the tenant. [boundAlias] is the `env:` alias the operator bound to
         * this tenant, if any. Every problem is collected; the messages never contain secret values.
         */
        fun parse(
            raw: String,
            boundAlias: String?,
            env: (String) -> String?,
        ): ParseResult {
            val props =
                try {
                    Properties().apply { load(StringReader(raw)) }
                } catch (e: IllegalArgumentException) {
                    LOG.debug("Tenant configuration is not valid Java properties", e)
                    return ParseResult.Invalid(listOf("the configuration is not valid Java properties text"))
                }
            val errors = mutableListOf<String>()

            fun get(key: String): String? = props.getProperty(PREFIX + key)?.trim()?.takeIf { it.isNotEmpty() }

            val token = merchantToken(get("merchantToken"), boundAlias, env, errors)
            val webhookUrl = url("webhookUrl", get("webhookUrl"), required = true, errors)
            val redirectUrl = url("redirectUrl", get("redirectUrl"), required = false, errors)
            val language = get("language")?.lowercase() ?: "uk"
            if (language !in LANGUAGES) errors += "language must be one of $LANGUAGES"
            val currencies = currencies(get("allowedCurrencies"), errors)
            val validity = validity(get("checkoutValiditySeconds"), errors)

            if (errors.isNotEmpty() || token == null || webhookUrl == null) return ParseResult.Invalid(errors)
            return ParseResult.Valid(
                TenantSettings(
                    merchantToken = token,
                    webhookUrl = webhookUrl,
                    redirectUrl = redirectUrl,
                    destination = get("destination"),
                    language = language,
                    allowedCurrencies = currencies,
                    checkoutValiditySeconds = validity,
                    basketItemCode = get("basketItemCode") ?: get("splitReceiverId")?.let { DEFAULT_ITEM_CODE },
                    splitReceiverId = get("splitReceiverId"),
                ),
            )
        }

        private fun merchantToken(
            value: String?,
            boundAlias: String?,
            env: (String) -> String?,
            errors: MutableList<String>,
        ): Secret? {
            val error: String
            when {
                value == null -> {
                    error = "merchantToken is required"
                }

                !value.startsWith("env:") -> {
                    return Secret(value)
                }

                !ALIAS.matches(value.removePrefix("env:")) -> {
                    error = "merchantToken env alias must match ${ALIAS.pattern}"
                }

                !value.removePrefix("env:").equals(boundAlias, ignoreCase = true) -> {
                    error = "merchantToken env alias is not bound to this tenant (system setting merchantTokenBindings)"
                }

                else -> {
                    val name = MERCHANT_TOKEN_ENV_PREFIX + value.removePrefix("env:").uppercase()
                    env(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return Secret(it) }
                    error = "merchantToken points at $name, which is not set"
                }
            }
            errors += error
            return null
        }

        private fun url(
            key: String,
            value: String?,
            required: Boolean,
            errors: MutableList<String>,
        ): URI? {
            if (value == null) {
                if (required) errors += "$key is required"
                return null
            }
            val uri = runCatching { URI(value) }.getOrNull()
            if (uri == null || uri.scheme !in setOf("https", "http") || uri.host.isNullOrEmpty()) {
                errors += "$key must be an absolute http(s) URL"
                return null
            }
            return uri
        }

        private fun currencies(
            value: String?,
            errors: MutableList<String>,
        ): Set<Currency> {
            val codes = value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("UAH")
            if (codes.isEmpty()) {
                errors += "allowedCurrencies must list at least one currency"
                return emptySet()
            }
            return codes
                .mapNotNull { code ->
                    try {
                        Money.currency(code).also { Money.ofMajor(java.math.BigDecimal.ZERO, it) }
                    } catch (e: InvalidAmountException) {
                        errors += "allowedCurrencies: ${e.message}"
                        null
                    }
                }.filter { currency ->
                    (currency.currencyCode in MONOBANK_CURRENCIES).also { accepted ->
                        if (!accepted) {
                            errors += "allowedCurrencies: monobank accepts only " +
                                "${MONOBANK_CURRENCIES.joinToString()}, not ${currency.currencyCode}"
                        }
                    }
                }.toSet()
        }

        private fun validity(
            value: String?,
            errors: MutableList<String>,
        ): Long {
            if (value == null) return DEFAULT_VALIDITY_SECONDS
            val seconds = value.toLongOrNull()
            if (seconds == null || seconds !in MIN_VALIDITY_SECONDS..MAX_VALIDITY_SECONDS) {
                errors += "checkoutValiditySeconds must be a number from $MIN_VALIDITY_SECONDS to $MAX_VALIDITY_SECONDS"
                return DEFAULT_VALIDITY_SECONDS
            }
            return seconds
        }
    }

    sealed interface ParseResult {
        data class Valid(
            val settings: TenantSettings,
        ) : ParseResult

        data class Invalid(
            val errors: List<String>,
        ) : ParseResult
    }
}

/** A secret value that never shows up in `toString`, logs or health output. */
class Secret(
    private val value: String,
) {
    fun reveal(): String = value

    override fun toString(): String = "***"

    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()
}
