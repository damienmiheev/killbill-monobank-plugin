/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.config

import io.github.damienmiheev.killbill.monobank.config.TenantSettings.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.killbill.billing.ObjectType
import org.killbill.billing.notification.plugin.api.ExtBusEvent
import org.killbill.billing.notification.plugin.api.ExtBusEventType
import java.util.UUID

class TenantSettingsTest {
    private val prefix = TenantSettings.PREFIX

    private fun parse(
        vararg lines: String,
        env: Map<String, String> = emptyMap(),
        boundAlias: String? = null,
    ): ParseResult = TenantSettings.parse(lines.joinToString("\n"), boundAlias, env::get)

    private fun valid(
        vararg lines: String,
        env: Map<String, String> = emptyMap(),
        boundAlias: String? = null,
    ): TenantSettings {
        val result = parse(*lines, env = env, boundAlias = boundAlias)
        assertThat(result).isInstanceOf(ParseResult.Valid::class.java)
        return (result as ParseResult.Valid).settings
    }

    private fun errors(
        vararg lines: String,
        env: Map<String, String> = emptyMap(),
        boundAlias: String? = null,
    ): List<String> {
        val result = parse(*lines, env = env, boundAlias = boundAlias)
        assertThat(result).isInstanceOf(ParseResult.Invalid::class.java)
        return (result as ParseResult.Invalid).errors
    }

    @Test
    fun `minimal config gets the defaults`() {
        val s = valid("${prefix}merchantToken=tok", "${prefix}webhookUrl=https://example.com/hook")

        assertThat(s.merchantToken.reveal()).isEqualTo("tok")
        assertThat(s.webhookUrl.toString()).isEqualTo("https://example.com/hook")
        assertThat(s.language).isEqualTo("uk")
        assertThat(s.checkoutValiditySeconds).isEqualTo(3600)
        assertThat(s.redirectUrl).isNull()
    }

    @Test
    fun `merchant token from the environment only under the fixed prefix`() {
        val s =
            valid(
                "${prefix}merchantToken=env:shop_1",
                "${prefix}webhookUrl=https://example.com/hook",
                env = mapOf("MONOBANK_MERCHANT_TOKEN_SHOP_1" to "from-env"),
                boundAlias = "SHOP_1",
            )
        assertThat(s.merchantToken.reveal()).isEqualTo("from-env")
    }

    @Test
    fun `a tenant cannot use an alias the operator did not bind to it, and learns nothing about other aliases`() {
        val env = mapOf("MONOBANK_MERCHANT_TOKEN_OTHER" to "someone-else's-token")
        val unbound =
            errors("${prefix}merchantToken=env:other", "${prefix}webhookUrl=https://example.com/hook", env = env)
        val boundToAnother =
            errors(
                "${prefix}merchantToken=env:other",
                "${prefix}webhookUrl=https://example.com/hook",
                env = env,
                boundAlias = "mine",
            )
        val notSetAnywhere =
            errors(
                "${prefix}merchantToken=env:nothing",
                "${prefix}webhookUrl=https://example.com/hook",
                env = env,
                boundAlias = "mine",
            )

        // The same answer whether the variable exists or not.
        assertThat(unbound).isEqualTo(boundToAnother).isEqualTo(notSetAnywhere)
        assertThat(unbound.single()).contains("not bound to this tenant")
        assertThat(unbound.joinToString()).doesNotContain("someone-else")
    }

    @Test
    fun `text that is not valid properties is reported, not thrown`() {
        assertThat(errors("${prefix}merchantToken=\\uZZZZ")).containsExactly(
            "the configuration is not valid Java properties text",
        )
    }

    @Test
    fun `an alias cannot reach another environment variable`() {
        val errs =
            errors(
                "${prefix}merchantToken=env:../KB_ADMIN_PASSWORD",
                "${prefix}webhookUrl=https://example.com/hook",
                env = mapOf("KB_ADMIN_PASSWORD" to "secret"),
            )
        assertThat(errs).anyMatch { it.contains("alias") }
        assertThat(errs.joinToString()).doesNotContain("secret")
    }

    @Test
    fun `a missing environment variable is reported without its value`() {
        val errs =
            errors(
                "${prefix}merchantToken=env:shop",
                "${prefix}webhookUrl=https://example.com/hook",
                boundAlias = "shop",
            )
        assertThat(errs).containsExactly("merchantToken points at MONOBANK_MERCHANT_TOKEN_SHOP, which is not set")
    }

    @Test
    fun `all problems are reported at once and never echo the token`() {
        val errs =
            errors(
                "${prefix}merchantToken=super-secret-token",
                "${prefix}webhookUrl=ftp://example.com",
                "${prefix}redirectUrl=not a url",
                "${prefix}language=de",
                "${prefix}checkoutValiditySeconds=5",
            )
        assertThat(errs).hasSize(4)
        assertThat(errs.joinToString()).doesNotContain("super-secret-token")
    }

    @Test
    fun `required keys`() {
        assertThat(errors("")).containsExactlyInAnyOrder("merchantToken is required", "webhookUrl is required")
    }

    @Test
    fun `secrets never show in toString`() {
        val s = valid("${prefix}merchantToken=tok-123", "${prefix}webhookUrl=https://example.com/hook")
        assertThat(s.toString()).doesNotContain("tok-123")
        assertThat(Secret("x").toString()).isEqualTo("***")
    }

    @Test
    fun `provider caches, does not cache read failures, and reloads on a change event`() {
        val tenant = UUID.randomUUID()
        var reads = 0
        var fail = true
        var config = "${prefix}merchantToken=tok\n${prefix}webhookUrl=https://example.com/hook"
        val provider =
            TenantSettingsProvider(
                readRaw = {
                    reads++
                    if (fail) throw ConfigUnavailableException("down")
                    config
                },
                boundAlias = { null },
                env = { null },
            )

        assertThat(provider.get(tenant)).isInstanceOf(ParseResult.Invalid::class.java)
        fail = false
        assertThat(provider.get(tenant)).isInstanceOf(ParseResult.Valid::class.java)
        provider.get(tenant)
        assertThat(reads).isEqualTo(2)

        config = ""
        provider.handleKillbillEvent(
            event(ExtBusEventType.TENANT_CONFIG_CHANGE, tenant, TenantSettingsProvider.CONFIG_KEY),
        )
        assertThat(provider.get(tenant)).isInstanceOf(ParseResult.Invalid::class.java)
        assertThat(reads).isEqualTo(3)
    }

    @Test
    fun `events for other keys, other types or no tenant are ignored`() {
        val tenant = UUID.randomUUID()
        var reads = 0
        val provider =
            TenantSettingsProvider(
                readRaw = {
                    reads++
                    "${prefix}merchantToken=tok\n${prefix}webhookUrl=https://example.com/hook"
                },
                boundAlias = { null },
                env = { null },
            )
        provider.get(tenant)
        provider.handleKillbillEvent(event(ExtBusEventType.TENANT_CONFIG_CHANGE, tenant, "PLUGIN_CONFIG_other"))
        provider.handleKillbillEvent(event(ExtBusEventType.ACCOUNT_CREATION, tenant, TenantSettingsProvider.CONFIG_KEY))
        provider.handleKillbillEvent(
            event(ExtBusEventType.TENANT_CONFIG_CHANGE, null, TenantSettingsProvider.CONFIG_KEY),
        )
        provider.handleKillbillEvent(event(ExtBusEventType.TENANT_CONFIG_CHANGE, tenant, null))
        provider.get(tenant)
        assertThat(reads).isEqualTo(1)

        provider.handleKillbillEvent(
            event(ExtBusEventType.TENANT_CONFIG_DELETION, tenant, TenantSettingsProvider.CONFIG_KEY),
        )
        provider.get(tenant)
        assertThat(reads).isEqualTo(2)
    }

    @Test
    fun `a change event during a read keeps the stale result out of the cache`() {
        val tenant = UUID.randomUUID()
        var reads = 0
        lateinit var provider: TenantSettingsProvider
        provider =
            TenantSettingsProvider(
                readRaw = {
                    reads++
                    if (reads == 1) {
                        // Kill Bill reports a change while the old value is being read.
                        provider.handleKillbillEvent(
                            event(ExtBusEventType.TENANT_CONFIG_CHANGE, tenant, TenantSettingsProvider.CONFIG_KEY),
                        )
                    }
                    "${prefix}merchantToken=tok\n${prefix}webhookUrl=https://example.com/hook"
                },
                boundAlias = { null },
                env = { null },
            )
        provider.get(tenant)
        provider.get(tenant)
        assertThat(reads).isEqualTo(2)
    }

    @Test
    fun `cached settings expire after the TTL`() {
        val tenant = UUID.randomUUID()
        var reads = 0
        var now = 0L
        val provider =
            TenantSettingsProvider(
                readRaw = {
                    reads++
                    "${prefix}merchantToken=tok\n${prefix}webhookUrl=https://example.com/hook"
                },
                boundAlias = { null },
                env = { null },
                nanoTime = { now },
                ttl = java.time.Duration.ofMinutes(10),
            )
        provider.get(tenant)
        now +=
            java.time.Duration
                .ofMinutes(9)
                .toNanos()
        provider.get(tenant)
        assertThat(reads).isEqualTo(1)
        now +=
            java.time.Duration
                .ofMinutes(2)
                .toNanos()
        provider.get(tenant)
        assertThat(reads).isEqualTo(2)
    }

    private fun event(
        type: ExtBusEventType,
        tenant: UUID?,
        metaData: String?,
    ): ExtBusEvent =
        object : ExtBusEvent {
            override fun getEventType() = type

            override fun getObjectType() = ObjectType.TENANT_KVS

            override fun getObjectId(): UUID? = null

            override fun getMetaData() = metaData

            override fun getAccountId(): UUID? = null

            override fun getTenantId() = tenant

            override fun getUserToken(): UUID? = null
        }
}
