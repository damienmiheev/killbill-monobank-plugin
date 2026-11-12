/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.mono

import io.github.damienmiheev.killbill.monobank.config.Secret
import io.github.damienmiheev.killbill.monobank.config.SystemSettings
import io.github.damienmiheev.monobank.acquiring.AcquiringConfig
import io.github.damienmiheev.monobank.acquiring.MonobankAcquiring
import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.RetryPolicy
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * One monobank client per merchant token, reused across calls so connections and the webhook key cache are shared.
 * A changed token simply gets a new client. The SDK never retries a POST whose outcome is unknown.
 */
class MonobankClients(
    private val settings: SystemSettings,
    private val options: MonobankClientOptions = defaultOptions(settings),
) {
    private val clients = ConcurrentHashMap<Secret, MonobankAcquiring>()

    fun forToken(token: Secret): MonobankAcquiring =
        clients.computeIfAbsent(token) {
            MonobankAcquiring(
                AcquiringConfig
                    .builder(it.reveal())
                    .cmsName(CMS_NAME)
                    .options(options)
                    .build(),
            )
        }

    /** How long one monobank request may take (no retries: see [defaultOptions]). */
    val requestTimeout: Duration get() = Duration.ofSeconds(settings.readTimeoutSeconds)

    /** Drops every client (on stop), so no merchant token outlives the plugin in memory. */
    fun clear() = clients.clear()

    companion object {
        const val CMS_NAME = "killbill-monobank"

        fun defaultOptions(settings: SystemSettings): MonobankClientOptions =
            MonobankClientOptions
                .builder()
                // SystemSettings allows plain http only for local and test hosts (*.internal, *.local, *.test).
                .allowPlainHttp(settings.apiBaseUrl.scheme == "http")
                .baseUrl(settings.apiBaseUrl.toString())
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .requestTimeout(Duration.ofSeconds(settings.readTimeoutSeconds))
                .userAgentSuffix(CMS_NAME)
                // No retries inside a call: a plugin call must end within Kill Bill's 30 s, and the reconcile job
                // retries on its own schedule. One request takes at most readTimeoutSeconds.
                .retryPolicy(RetryPolicy.builder().maxRetries(0).build())
                .build()

        private const val CONNECT_TIMEOUT_SECONDS = 5L
    }
}
