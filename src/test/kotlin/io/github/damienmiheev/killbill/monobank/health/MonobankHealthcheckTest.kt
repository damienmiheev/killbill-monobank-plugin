/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.health

import io.github.damienmiheev.killbill.monobank.config.TenantSettingsProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

class MonobankHealthcheckTest {
    private val tenants =
        TenantSettingsProvider(
            readRaw = { error("must not be read without a tenant") },
            boundAlias = { null },
            env = { null },
        )

    @Test
    fun `an unreachable database is unhealthy and says only that`() {
        val status =
            MonobankHealthcheck(failingDataSource(SQLException("connection refused to 10.0.0.5", "08001")), tenants)
                .getHealthStatus(null, null)

        assertThat(status.isHealthy).isFalse
        assertThat(status.details).containsExactlyEntriesOf(mapOf("database" to "unreachable"))
    }

    @Test
    fun `an unexpected failure is unhealthy without details`() {
        val status =
            MonobankHealthcheck(failingDataSource(IllegalStateException("boom: secret")), tenants)
                .getHealthStatus(null, null)

        assertThat(status.isHealthy).isFalse
        assertThat(status.details.toString()).doesNotContain("boom").doesNotContain("secret")
    }

    private fun failingDataSource(error: Throwable): DataSource =
        object : DataSource {
            override fun getConnection(): Connection = throw error

            override fun getConnection(
                username: String?,
                password: String?,
            ): Connection = throw error

            override fun getLogWriter(): PrintWriter? = null

            override fun setLogWriter(out: PrintWriter?) = Unit

            override fun setLoginTimeout(seconds: Int) = Unit

            override fun getLoginTimeout(): Int = 0

            override fun getParentLogger(): Logger = Logger.getGlobal()

            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("not a wrapper")

            override fun isWrapperFor(iface: Class<*>?): Boolean = false
        }
}
