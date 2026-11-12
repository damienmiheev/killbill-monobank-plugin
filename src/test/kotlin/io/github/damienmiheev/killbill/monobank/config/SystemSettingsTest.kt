/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class SystemSettingsTest {
    private val p = SystemSettings.PREFIX
    private val key1 = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val key2 = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })

    private fun minimal(): MutableMap<String, String> =
        mutableMapOf(
            "${p}kbUsername" to "monobank-plugin",
            "${p}kbPassword" to "pw",
            "${p}cardKeys" to "k2:$key2,k1:$key1",
            "${p}tokenHmacKey" to key1,
        )

    @Test
    fun `defaults and the first card key for new writes`() {
        val s = SystemSettings.load(minimal()::get) { null }

        assertThat(s.currentCardKeyId).isEqualTo("k2")
        assertThat(s.cardKeys.keys).containsExactly("k2", "k1")
        assertThat(s.apiBaseUrl.toString()).isEqualTo("https://api.monobank.ua")
        assertThat(s.readTimeoutSeconds).isEqualTo(15)
        assertThat(s.reconcileIntervalSeconds).isEqualTo(300)
        assertThat(s.notificationSharedSecret).isNull()
    }

    @Test
    fun `values may come from the environment`() {
        val props = minimal().apply { put("${p}kbPassword", "env:PLUGIN_PW") }
        val s = SystemSettings.load(props::get, mapOf("PLUGIN_PW" to "from-env")::get)
        assertThat(s.kbPassword.reveal()).isEqualTo("from-env")
    }

    @Test
    fun `refuses to start with every problem listed and no secret echoed`() {
        val props =
            mutableMapOf(
                "${p}kbPassword" to "env:MISSING",
                "${p}cardKeys" to "k1:short,k1:$key1,bad id:$key1",
                "${p}tokenHmacKey" to "c2hvcnQ=",
                "${p}apiBaseUrl" to "nope",
                "${p}readTimeoutSeconds" to "0",
            )
        assertThatThrownBy { SystemSettings.load(props::get) { null } }
            .isInstanceOf(InvalidSettingsException::class.java)
            .satisfies({ e ->
                val errors = (e as InvalidSettingsException).errors
                assertThat(errors).contains(
                    "kbUsername is required",
                    "kbPassword points at environment variable MISSING, which is not set",
                    "tokenHmacKey must be 32 bytes, base64-encoded",
                    "apiBaseUrl must be an absolute https URL (http only for local and test hosts)",
                    "readTimeoutSeconds must be a number from 1 to 120",
                )
                assertThat(errors).anyMatch { it.startsWith("cardKeys[k1] must be 32 bytes") }
                assertThat(errors).anyMatch { it.contains("must be id:base64key") }
                assertThat(errors.joinToString()).doesNotContain(key1)
            })
    }

    @Test
    fun `card keys may come from the environment, and a duplicate key id is refused`() {
        val fromEnv = minimal().apply { put("${p}cardKeys", "env:CARD_KEYS") }
        val s = SystemSettings.load(fromEnv::get, mapOf("CARD_KEYS" to "k9:$key1")::get)
        assertThat(s.currentCardKeyId).isEqualTo("k9")

        val duplicate = minimal().apply { put("${p}cardKeys", "k1:$key1,k1:$key2") }
        assertThatThrownBy { SystemSettings.load(duplicate::get) { null } }
            .isInstanceOf(InvalidSettingsException::class.java)
            .hasMessageContaining("key id k1 appears twice")
    }

    @Test
    fun `merchant token bindings by API key, the alias after the last colon`() {
        val props = minimal().apply { put("${p}merchantTokenBindings", "shop-a:alias_a, key:with:colons:b") }
        val s = SystemSettings.load(props::get) { null }
        assertThat(s.merchantTokenBindings).containsExactlyEntriesOf(
            mapOf(
                "shop-a" to "alias_a",
                "key:with:colons" to "b",
            ),
        )

        val bad = minimal().apply { put("${p}merchantTokenBindings", "x:bad alias,:a,k:a,k:b") }
        assertThatThrownBy { SystemSettings.load(bad::get) { null } }
            .isInstanceOf(InvalidSettingsException::class.java)
            .satisfies({ e ->
                assertThat((e as InvalidSettingsException).errors).containsExactly(
                    "merchantTokenBindings: each entry must be apiKey:alias",
                    "merchantTokenBindings: each entry must be apiKey:alias",
                    "merchantTokenBindings: an API key appears twice",
                )
            })
    }

    @Test
    fun `plain http only for local and test hosts, and a short shared secret is refused`() {
        for (url in listOf(
            "http://localhost:9000",
            "http://host.testcontainers.internal:1234",
            "https://mono.example",
        )) {
            val props = minimal().apply { put("${p}apiBaseUrl", url) }
            assertThat(SystemSettings.load(props::get) { null }.apiBaseUrl.toString()).isEqualTo(url)
        }
        val plain = minimal().apply { put("${p}apiBaseUrl", "http://api.monobank.ua") }
        assertThatThrownBy { SystemSettings.load(plain::get) { null } }.hasMessageContaining("apiBaseUrl must be")

        val short = minimal().apply { put("${p}notificationSharedSecret", "too-short") }
        assertThatThrownBy { SystemSettings.load(short::get) { null } }
            .hasMessageContaining("notificationSharedSecret must be at least 32 characters")
    }

    @Test
    fun `toString shows no secrets`() {
        val sharedSecret = "s".repeat(40)
        val props =
            minimal().apply {
                put("${p}kbPassword", "the-plugin-password")
                put("${p}notificationSharedSecret", sharedSecret)
            }
        val text = SystemSettings.load(props::get) { null }.toString()
        assertThat(text)
            .doesNotContain(key1)
            .doesNotContain(key2)
            .doesNotContain("the-plugin-password")
            .doesNotContain(sharedSecret)
            .contains("notificationSharedSecret=set")
    }
}
