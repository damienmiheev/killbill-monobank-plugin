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
                .build()

        private const val CONNECT_TIMEOUT_SECONDS = 5L
    }
}
