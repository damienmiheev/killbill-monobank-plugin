/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.it

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** The bundle inside a real Kill Bill: it loads, registers, reports health and reads tenant settings. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PluginLifecycleIT {
    private lateinit var stack: KillBillStack

    @BeforeAll
    fun start() {
        stack = KillBillStack()
        stack.start()
    }

    @AfterAll
    fun stop() {
        if (::stack.isInitialized) stack.close()
    }

    @Test
    fun `the bundle resolves and runs inside Kill Bill`() {
        val plugin = stack.pluginInfo()

        assertThat(plugin).describedAs("killbill-monobank in nodesInfo").isNotNull
        assertThat(plugin!!.path("state").asText()).isEqualTo("RUNNING")
        assertThat(stack.pluginState).isEqualTo("RUNNING")
    }

    @Test
    fun `health without a tenant reports the database and schema`() {
        val response = stack.get("/plugins/killbill-monobank/healthcheck", auth = false)

        assertThat(response.status).isEqualTo(200)
        val json = response.json()
        assertThat(json.path("healthy").asBoolean()).isTrue
        assertThat(json.path("details").path("database").asText()).isEqualTo("reachable")
        assertThat(json.path("details").path("schema").asText()).isEqualTo("complete")
    }

    @Test
    fun `a tenant without configuration is unhealthy`() {
        val tenant = stack.createTenant()

        val response = stack.get("/plugins/killbill-monobank/healthcheck", tenant)

        assertThat(response.status).isEqualTo(503)
        assertThat(
            response
                .json()
                .path("details")
                .path("tenantConfig")
                .asText(),
        ).isEqualTo("invalid")
    }

    @Test
    fun `a tenant's configuration is read, with the merchant token from the environment, and reloaded on change`() {
        val tenant = stack.createTenant(KillBillStack.BOUND_API_KEY)
        stack.uploadPluginConfig(
            tenant,
            """
            org.killbill.billing.plugin.monobank.merchantToken=env:it
            org.killbill.billing.plugin.monobank.webhookUrl=https://example.com/webhooks/monobank
            org.killbill.billing.plugin.monobank.allowedCurrencies=UAH,USD
            """.trimIndent(),
        )

        val healthy = stack.get("/plugins/killbill-monobank/healthcheck", tenant)
        assertThat(healthy.status).describedAs(healthy.body).isEqualTo(200)
        assertThat(
            healthy
                .json()
                .path("details")
                .path("tenantConfig")
                .asText(),
        ).isEqualTo("valid")
        assertThat(healthy.body).doesNotContain("test-merchant-token")

        // An invalid upload replaces the cached settings once Kill Bill's change event arrives.
        stack.uploadPluginConfig(tenant, "org.killbill.billing.plugin.monobank.merchantToken=env:other")
        val deadline = System.nanoTime() + 30_000_000_000L
        var response = stack.get("/plugins/killbill-monobank/healthcheck", tenant)
        while (response.status == 200 && System.nanoTime() < deadline) {
            Thread.sleep(500)
            response = stack.get("/plugins/killbill-monobank/healthcheck", tenant)
        }
        assertThat(response.status).isEqualTo(503)
        assertThat(
            response
                .json()
                .path("details")
                .path("tenantConfigErrors")
                .toString(),
        ).contains("webhookUrl is required")
            .contains("not bound to this tenant")
    }

    @Test
    fun `a tenant cannot use a merchant token bound to another tenant`() {
        val tenant = stack.createTenant()
        stack.uploadPluginConfig(
            tenant,
            """
            org.killbill.billing.plugin.monobank.merchantToken=env:it
            org.killbill.billing.plugin.monobank.webhookUrl=https://example.com/webhooks/monobank
            """.trimIndent(),
        )

        val response = stack.get("/plugins/killbill-monobank/healthcheck", tenant)

        assertThat(response.status).isEqualTo(503)
        assertThat(response.body).contains("not bound to this tenant").doesNotContain("test-merchant-token")
    }

    @Test
    fun `unknown plugin routes are 404`() {
        assertThat(stack.get("/plugins/killbill-monobank/nope", auth = false).status).isEqualTo(404)
    }
}
