/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.it

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The plugin never runs half-configured: without its schema or with invalid settings it does not start. */
class RefusesToStartIT {
    @Test
    fun `without its schema the plugin does not run and serves nothing`() {
        KillBillStack(applyPluginSchema = false).start().use { stack ->
            assertThat(stack.pluginState).isEqualTo("STOPPED")
            assertThat(stack.get("/plugins/killbill-monobank/healthcheck", auth = false).status).isEqualTo(404)
            assertThat(stack.killbillLogs()).contains(
                "The monobank plugin's tables are missing or out of date",
                "table monobank_transactions is missing",
            )
        }
    }

    @Test
    fun `with invalid system settings the plugin does not run`() {
        val badKeys = mapOf("KB_org_killbill_billing_plugin_monobank_cardKeys" to "bad")
        KillBillStack(extraEnv = badKeys).start().use { stack ->
            assertThat(stack.pluginState).isEqualTo("STOPPED")
            assertThat(stack.killbillLogs())
                .contains("Invalid monobank plugin settings: cardKeys: each entry must be id:base64key")
        }
    }
}
