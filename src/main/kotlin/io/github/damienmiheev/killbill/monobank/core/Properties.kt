/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import org.killbill.billing.payment.api.PluginProperty
import java.util.UUID

/**
 * The plugin properties a caller may pass (design A5). Unknown keys are ignored, and no property can mark anything
 * paid or set a card token.
 */
class Properties(
    properties: Iterable<PluginProperty>?,
) {
    private val values: Map<String, Any?> = properties?.associate { it.key to it.value }.orEmpty()

    fun string(key: String): String? = values[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    fun flag(key: String): Boolean = string(key)?.equals("true", ignoreCase = true) == true

    fun long(key: String): Long? = string(key)?.toLongOrNull()

    fun uuid(key: String): UUID? = string(key)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    companion object {
        const val HOSTED = "hosted"
        const val SAVE_CARD = "saveCard"
        const val REDIRECT_URL = "redirectUrl"
        const val DESTINATION = "destination"
        const val VALIDITY_SECONDS = "validitySeconds"
        const val CALLER_REF = "callerRef"
        const val BASKET_ORDER = "basketOrder"
        const val INITIATION_KIND = "initiationKind"

        /** Set by Kill Bill's invoice payment control plugin when a payment pays an invoice. */
        const val KB_INVOICE_ID = "IPCD_INVOICE_ID"

        // Returned on transactions.
        const val FORM_URL = "formUrl"
        const val TDS_URL = "tdsUrl"
        const val INVOICE_ID = "invoiceId"
        const val EXPIRES_AT = "expiresAt"
        const val REFERENCE = "reference"
    }
}
