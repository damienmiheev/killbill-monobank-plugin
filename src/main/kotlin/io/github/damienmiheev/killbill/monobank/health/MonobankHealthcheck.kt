/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.health

import io.github.damienmiheev.killbill.monobank.config.TenantSettings
import io.github.damienmiheev.killbill.monobank.config.TenantSettingsProvider
import io.github.damienmiheev.killbill.monobank.db.SchemaCheck
import org.killbill.billing.osgi.api.Healthcheck
import org.killbill.billing.tenant.api.Tenant
import org.slf4j.LoggerFactory
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Health without side effects: never calls monobank per probe, never returns secrets or customer data
 * (Kill Bill serves the plugin's health route without authentication).
 */
class MonobankHealthcheck(
    private val dataSource: DataSource,
    private val tenants: TenantSettingsProvider,
) : Healthcheck {
    override fun getHealthStatus(
        tenant: Tenant?,
        properties: MutableMap<Any?, Any?>?,
    ): Healthcheck.HealthStatus =
        try {
            status(tenant)
        } catch (e: RuntimeException) {
            // health must answer, and never with internals
            LOG.warn("Health check failed", e)
            Healthcheck.HealthStatus(false, mapOf("error" to "health check failed"))
        }

    private fun status(tenant: Tenant?): Healthcheck.HealthStatus {
        val details = linkedMapOf<String, Any>()
        val schemaMissing =
            try {
                SchemaCheck.missing(dataSource)
            } catch (e: SQLException) {
                LOG.warn("Health check could not reach the database: {}", e.sqlState)
                return Healthcheck.HealthStatus(false, mapOf("database" to "unreachable"))
            }
        details["database"] = "reachable"
        details["schema"] = if (schemaMissing.isEmpty()) "complete" else "incomplete"
        var healthy = schemaMissing.isEmpty()

        if (tenant != null) {
            when (val settings = tenants.get(tenant.id)) {
                is TenantSettings.ParseResult.Valid -> {
                    details["tenantConfig"] = "valid"
                }

                is TenantSettings.ParseResult.Invalid -> {
                    details["tenantConfig"] = "invalid"
                    details["tenantConfigErrors"] = settings.errors
                    healthy = false
                }
            }
        }
        return Healthcheck.HealthStatus(healthy, details)
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(MonobankHealthcheck::class.java)
    }
}
