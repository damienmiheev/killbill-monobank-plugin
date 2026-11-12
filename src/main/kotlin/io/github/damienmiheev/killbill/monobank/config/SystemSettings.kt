/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.config

import java.net.URI
import java.util.Base64

/**
 * Settings for the whole plugin, read from Kill Bill's system properties (or `KB_…` environment variables).
 * These are the operator's, not a tenant's: a value may be `env:NAME` to read any environment variable.
 */
class SystemSettings private constructor(
    /** The plugin's own Kill Bill user (role with `payment:transition` only). */
    val kbUsername: String,
    val kbPassword: Secret,
    /** Card-token encryption keys by id; [currentCardKeyId] is used for new writes. */
    val cardKeys: Map<String, Secret>,
    val currentCardKeyId: String,
    val tokenHmacKey: Secret,
    val notificationSharedSecret: Secret?,
    /** The `env:` alias each tenant may use for its merchant token, by the tenant's Kill Bill API key. */
    val merchantTokenBindings: Map<String, String>,
    val apiBaseUrl: URI,
    val readTimeoutSeconds: Long,
    val reconcileIntervalSeconds: Long,
    val statementMinIntervalSeconds: Long,
) {
    override fun toString(): String =
        "SystemSettings(kbUsername=$kbUsername, cardKeys=${cardKeys.keys}, currentCardKeyId=$currentCardKeyId, " +
            "notificationSharedSecret=${if (notificationSharedSecret == null) "unset" else "set"}, " +
            "merchantTokenBindings=${merchantTokenBindings.size}, " +
            "apiBaseUrl=$apiBaseUrl, readTimeoutSeconds=$readTimeoutSeconds, " +
            "reconcileIntervalSeconds=$reconcileIntervalSeconds, statementMinIntervalSeconds=$statementMinIntervalSeconds)"

    companion object {
        const val PREFIX = "org.killbill.billing.plugin.monobank."

        private const val KEY_BYTES = 32
        private val KEY_ID = Regex("[A-Za-z0-9_-]{1,32}")
        private const val MIN_SHARED_SECRET_LENGTH = 32

        /** Hosts that may be reached over plain http (tests and local fakes); anything else must be https. */
        private val PLAIN_HTTP_HOST = Regex("localhost|127\\.0\\.0\\.1|\\[::1]|.+\\.(internal|local|test)")

        /**
         * Reads and validates every setting. Throws [InvalidSettingsException] listing all problems,
         * so the plugin refuses to start instead of failing on the first payment.
         */
        fun load(
            property: (String) -> String?,
            env: (String) -> String?,
        ): SystemSettings {
            val errors = mutableListOf<String>()

            fun raw(key: String): String? = property(PREFIX + key)?.trim()?.takeIf { it.isNotEmpty() }

            fun resolved(key: String): String? {
                val value = raw(key) ?: return null
                if (!value.startsWith("env:")) return value
                val name = value.removePrefix("env:")
                val fromEnv = env(name)?.trim()?.takeIf { it.isNotEmpty() }
                if (fromEnv == null) errors += "$key points at environment variable $name, which is not set"
                return fromEnv
            }

            fun required(key: String): String? =
                resolved(key).also {
                    if (it == null &&
                        raw(key) == null
                    ) {
                        errors += "$key is required"
                    }
                }

            val username = required("kbUsername")
            val password = required("kbPassword")
            val cardKeys = cardKeys(required("cardKeys"), errors)
            val hmacKey = required("tokenHmacKey")?.also { key(it, "tokenHmacKey", errors) }
            val sharedSecret = sharedSecret(resolved("notificationSharedSecret"), errors)
            val bindings = bindings(raw("merchantTokenBindings"), errors)
            val baseUri = baseUrl(raw("apiBaseUrl") ?: "https://api.monobank.ua", errors)
            val readTimeout = seconds(raw("readTimeoutSeconds"), "readTimeoutSeconds", 15, 1..120, errors)
            val reconcile = seconds(raw("reconcileIntervalSeconds"), "reconcileIntervalSeconds", 300, 30..3600, errors)
            val statement =
                seconds(raw("statementMinIntervalSeconds"), "statementMinIntervalSeconds", 60, 1..3600, errors)

            if (errors.isNotEmpty()) throw InvalidSettingsException(errors)
            return SystemSettings(
                kbUsername = username!!,
                kbPassword = Secret(password!!),
                cardKeys = cardKeys.first,
                currentCardKeyId = cardKeys.second!!,
                tokenHmacKey = Secret(hmacKey!!),
                notificationSharedSecret = sharedSecret?.let(::Secret),
                merchantTokenBindings = bindings,
                apiBaseUrl = baseUri!!,
                readTimeoutSeconds = readTimeout,
                reconcileIntervalSeconds = reconcile,
                statementMinIntervalSeconds = statement,
            )
        }

        private fun sharedSecret(
            value: String?,
            errors: MutableList<String>,
        ): String? {
            if (value != null && value.length < MIN_SHARED_SECRET_LENGTH) {
                errors += "notificationSharedSecret must be at least $MIN_SHARED_SECRET_LENGTH characters"
            }
            return value
        }

        private fun baseUrl(
            value: String,
            errors: MutableList<String>,
        ): URI? {
            val uri = runCatching { URI(value) }.getOrNull()
            val host = uri?.host.orEmpty()
            val valid =
                host.isNotEmpty() &&
                    (uri?.scheme == "https" || (uri?.scheme == "http" && PLAIN_HTTP_HOST.matches(host)))
            if (!valid) errors += "apiBaseUrl must be an absolute https URL (http only for local and test hosts)"
            return uri.takeIf { valid }
        }

        /**
         * `apiKey:alias[,apiKey:alias…]`: which `env:` alias each tenant, named by its Kill Bill API key, may use for
         * its merchant token. The alias follows the last `:`.
         */
        private fun bindings(
            value: String?,
            errors: MutableList<String>,
        ): Map<String, String> {
            val result = linkedMapOf<String, String>()
            for (entry in value
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()) {
                val apiKey = entry.substringBeforeLast(':', missingDelimiterValue = "")
                val alias = entry.substringAfterLast(':', missingDelimiterValue = "")
                when {
                    apiKey.isEmpty() || !TenantSettings.ALIAS.matches(alias) -> {
                        errors += "merchantTokenBindings: each entry must be apiKey:alias"
                    }

                    apiKey in result -> {
                        errors += "merchantTokenBindings: an API key appears twice"
                    }

                    else -> {
                        result[apiKey] = alias
                    }
                }
            }
            return result
        }

        /** `id:base64key[,id:base64key…]`; the first entry is the key for new writes. */
        private fun cardKeys(
            value: String?,
            errors: MutableList<String>,
        ): Pair<Map<String, Secret>, String?> {
            if (value == null) return emptyMap<String, Secret>() to null
            val keys = linkedMapOf<String, Secret>()
            for (entry in value.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val id = entry.substringBefore(':', missingDelimiterValue = "")
                val key = entry.substringAfter(':', missingDelimiterValue = "")
                when {
                    !KEY_ID.matches(id) -> {
                        errors +=
                            "cardKeys: each entry must be id:base64key with id matching ${KEY_ID.pattern}"
                    }

                    id in keys -> {
                        errors += "cardKeys: key id $id appears twice"
                    }

                    key(key, "cardKeys[$id]", errors) -> {
                        keys[id] = Secret(key)
                    }
                }
            }
            if (keys.isEmpty() &&
                errors.none { it.startsWith("cardKeys") }
            ) {
                errors += "cardKeys must hold at least one key"
            }
            return keys to keys.keys.firstOrNull()
        }

        private fun key(
            base64: String,
            name: String,
            errors: MutableList<String>,
        ): Boolean {
            val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
            if (bytes == null || bytes.size != KEY_BYTES) {
                errors += "$name must be $KEY_BYTES bytes, base64-encoded"
                return false
            }
            return true
        }

        private fun seconds(
            value: String?,
            name: String,
            default: Long,
            range: IntRange,
            errors: MutableList<String>,
        ): Long {
            if (value == null) return default
            val parsed = value.toLongOrNull()
            if (parsed == null || parsed !in range.first.toLong()..range.last.toLong()) {
                errors += "$name must be a number from ${range.first} to ${range.last}"
                return default
            }
            return parsed
        }
    }
}

class InvalidSettingsException(
    val errors: List<String>,
) : IllegalStateException("Invalid monobank plugin settings: " + errors.joinToString("; "))
