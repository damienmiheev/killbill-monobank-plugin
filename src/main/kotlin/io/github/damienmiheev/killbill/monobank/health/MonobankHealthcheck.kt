/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.health

import io.github.damienmiheev.killbill.monobank.config.TenantSettings
import io.github.damienmiheev.killbill.monobank.config.TenantSettingsProvider
import io.github.damienmiheev.killbill.monobank.core.PluginMetrics
import io.github.damienmiheev.killbill.monobank.db.SchemaCheck
import org.killbill.billing.osgi.api.Healthcheck
import org.killbill.billing.tenant.api.Tenant
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * Health without side effects: never calls monobank per probe, never returns secrets or customer data
 * (Kill Bill serves the plugin's health route without authentication).
 */
class MonobankHealthcheck(
    private val dataSource: DataSource,
    private val tenants: TenantSettingsProvider,
    private val metrics: PluginMetrics? = null,
    /** How often the reconcile job runs; with [metrics], a job that stopped running makes the plugin unhealthy. */
    private val reconcileInterval: Duration? = null,
    private val now: () -> Instant = Instant::now,
) : Healthcheck {
    private val startedAt = now()

    /** Kill Bill's own health route, open to anyone: no counters, no configuration details. */
    override fun getHealthStatus(
        tenant: Tenant?,
        properties: MutableMap<Any?, Any?>?,
    ): Healthcheck.HealthStatus = safely { status(tenant, detailed = false) }

    /** For an authenticated Kill Bill caller: the counters and the tenant's configuration errors too. */
    fun detailedStatus(tenant: Tenant?): Healthcheck.HealthStatus = safely { status(tenant, detailed = true) }

    @Suppress("TooGenericExceptionCaught")
    private fun safely(block: () -> Healthcheck.HealthStatus): Healthcheck.HealthStatus =
        try {
            block()
        } catch (e: Exception) {
            // health must answer, and never with internals
            LOG.warn("Health check failed", e)
            Healthcheck.HealthStatus(false, mapOf("error" to "health check failed"))
        }

    private fun status(
        tenant: Tenant?,
        detailed: Boolean,
    ): Healthcheck.HealthStatus {
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
        if (detailed) metrics?.let { details["counters"] = it.snapshot() }
        if (reconcileStalled()) {
            details["reconcile"] = "stalled"
            healthy = false
        }

        if (tenant != null) {
            when (val settings = tenants.get(tenant.id)) {
                is TenantSettings.ParseResult.Valid -> {
                    details["tenantConfig"] = "valid"
                }

                is TenantSettings.ParseResult.Invalid -> {
                    details["tenantConfig"] = "invalid"
                    if (detailed) details["tenantConfigErrors"] = settings.errors
                    healthy = false
                }
            }
        }
        return Healthcheck.HealthStatus(healthy, details)
    }

    /** No reconcile run for three intervals (plus a minute) since the plugin started or the last run. */
    private fun reconcileStalled(): Boolean {
        val interval = reconcileInterval ?: return false
        val last = metrics?.lastReconcileRun?.get() ?: startedAt
        return last.plus(interval.multipliedBy(STALL_INTERVALS)).plus(STALL_MARGIN).isBefore(now())
    }

    private companion object {
        const val STALL_INTERVALS = 3L
        val STALL_MARGIN: Duration = Duration.ofMinutes(1)
        val LOG = LoggerFactory.getLogger(MonobankHealthcheck::class.java)
    }
}
