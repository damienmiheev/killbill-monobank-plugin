/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Counters an operator alerts on (design A11); reported by the health check. */
class PluginMetrics {
    val stateConflict = AtomicLong()
    val unexpectedReversal = AtomicLong()
    val amountMismatch = AtomicLong()
    val unknownClosed = AtomicLong()
    val signatureFailed = AtomicLong()
    val holdsNearExpiry = AtomicLong()
    val operationStale = AtomicLong()

    /** Invoice charges sent with the default basket item because the invoice's item couldn't be built. */
    val basketFallback = AtomicLong()
    val lastReconcileRun = AtomicReference<Instant?>(null)
    val lastWebhookVerified = AtomicReference<Instant?>(null)
    val lastMonobankCall = AtomicReference<Instant?>(null)

    fun snapshot(): Map<String, Any> =
        linkedMapOf<String, Any>(
            "stateConflict" to stateConflict.get(),
            "unexpectedReversal" to unexpectedReversal.get(),
            "amountMismatch" to amountMismatch.get(),
            "unknownClosed" to unknownClosed.get(),
            "signatureFailed" to signatureFailed.get(),
            "holdsNearExpiry" to holdsNearExpiry.get(),
            "operationStale" to operationStale.get(),
            "basketFallback" to basketFallback.get(),
        ).apply {
            lastReconcileRun.get()?.let { put("lastReconcileRun", it.toString()) }
            lastWebhookVerified.get()?.let { put("lastWebhookVerified", it.toString()) }
            lastMonobankCall.get()?.let { put("lastMonobankCall", it.toString()) }
        }
}
