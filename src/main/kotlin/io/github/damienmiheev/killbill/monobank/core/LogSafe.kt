/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

/**
 * A value an outside caller controls (a webhook body before its signature is checked, a request path), made safe for
 * one log line: line breaks and other control characters become `_`, and it is cut to [max] characters, so it cannot
 * forge log entries or flood the log.
 */
internal fun logSafe(
    value: String?,
    max: Int = 80,
): String {
    if (value == null) return "<none>"
    val cleaned = value.map { if (it.isISOControl()) '_' else it }.joinToString("")
    return if (cleaned.length > max) cleaned.take(max) + "…" else cleaned
}
