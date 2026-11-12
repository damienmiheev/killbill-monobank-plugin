/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.config

import io.github.damienmiheev.killbill.monobank.PLUGIN_NAME
import org.killbill.billing.notification.plugin.api.ExtBusEvent
import org.killbill.billing.notification.plugin.api.ExtBusEventType
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillEventDispatcher
import org.killbill.billing.util.callcontext.TenantContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-tenant settings, read from Kill Bill's tenant key `PLUGIN_CONFIG_killbill-monobank`.
 *
 * Cached until Kill Bill reports that the tenant's configuration changed, and for at most [ttl] (a safety net for
 * a change event that never reaches this node). A read that races with a change event is not cached, and a failure
 * to read is never cached. There is no global fallback: a tenant without valid settings cannot make payments.
 */
class TenantSettingsProvider(
    private val readRaw: (UUID) -> String?,
    private val boundAlias: (UUID) -> String?,
    private val env: (String) -> String?,
    private val nanoTime: () -> Long = System::nanoTime,
    private val ttl: Duration = Duration.ofMinutes(10),
) : OSGIKillbillEventDispatcher.OSGIKillbillEventHandler {
    private class Entry(
        val result: TenantSettings.ParseResult,
        val loadedAt: Long,
    )

    private val cache = ConcurrentHashMap<UUID, Entry>()
    private val generations = ConcurrentHashMap<UUID, AtomicLong>()

    /** The tenant's settings, or why there are none. */
    fun get(tenantId: UUID): TenantSettings.ParseResult {
        val now = nanoTime()
        cache[tenantId]?.takeIf { now - it.loadedAt < ttl.toNanos() }?.let { return it.result }

        val generation = generation(tenantId)
        val before = generation.get()
        val result =
            try {
                load(tenantId)
            } catch (e: ConfigUnavailableException) {
                return TenantSettings.ParseResult.Invalid(listOf(e.message ?: "configuration unavailable"))
            }
        // Store only if no change event arrived while reading; otherwise the next call reads again.
        if (generation.get() == before) cache[tenantId] = Entry(result, now)
        return result
    }

    private fun load(tenantId: UUID): TenantSettings.ParseResult {
        val raw = readRaw(tenantId)
        if (raw.isNullOrBlank()) {
            return TenantSettings.ParseResult.Invalid(listOf("no plugin configuration uploaded for this tenant"))
        }
        return TenantSettings.parse(raw, boundAlias(tenantId), env).also {
            if (it is TenantSettings.ParseResult.Invalid) {
                LOG.warn("Invalid monobank configuration for tenant {}: {}", tenantId, it.errors)
            }
        }
    }

    private fun generation(tenantId: UUID): AtomicLong = generations.computeIfAbsent(tenantId) { AtomicLong() }

    override fun handleKillbillEvent(event: ExtBusEvent) {
        val type = event.eventType
        if (type != ExtBusEventType.TENANT_CONFIG_CHANGE && type != ExtBusEventType.TENANT_CONFIG_DELETION) return
        if (event.metaData?.contains(CONFIG_KEY) != true) return
        val tenantId = event.tenantId ?: return
        generation(tenantId).incrementAndGet()
        cache.remove(tenantId)
        LOG.info("monobank configuration for tenant {} changed; reloading on next use", tenantId)
    }

    companion object {
        const val CONFIG_KEY = "PLUGIN_CONFIG_$PLUGIN_NAME"
        private val LOG = LoggerFactory.getLogger(TenantSettingsProvider::class.java)

        /** Reads the tenant's raw configuration through Kill Bill's tenant API. */
        fun killbillReader(
            values: (String, TenantContext) -> List<String>?,
            contextFor: (UUID) -> TenantContext,
        ): (UUID) -> String? =
            { tenantId ->
                try {
                    values(CONFIG_KEY, contextFor(tenantId))?.firstOrNull()
                } catch (e: org.killbill.billing.tenant.api.TenantApiException) {
                    LOG.warn("Could not read monobank configuration for tenant {}: {}", tenantId, e.code)
                    throw ConfigUnavailableException("could not read the tenant's configuration (code ${e.code})", e)
                } catch (e: RuntimeException) {
                    // e.g. Kill Bill's database: unavailable, never cached
                    LOG.warn("Could not read monobank configuration for tenant {}", tenantId, e)
                    throw ConfigUnavailableException("could not read the tenant's configuration", e)
                }
            }
    }
}

/** Kill Bill could not return the tenant's configuration right now. */
class ConfigUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
