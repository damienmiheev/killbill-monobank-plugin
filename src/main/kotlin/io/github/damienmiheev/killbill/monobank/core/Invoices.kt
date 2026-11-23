/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import org.joda.time.DateTime
import org.killbill.billing.invoice.api.InvoiceItemType
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI
import java.math.BigDecimal
import java.util.UUID

/** A Kill Bill invoice, as much of it as the plugin reads for a charge's receipt item (design A5). */
data class InvoiceView(
    val id: UUID,
    val accountId: UUID,
    val currency: String,
    val items: List<Line>,
) {
    data class Line(
        val id: UUID,
        val type: InvoiceItemType,
        val amount: BigDecimal,
        val linkedItemId: UUID?,
        val description: String?,
        val planName: String?,
        val prettyPlanName: String?,
        val productName: String?,
        val prettyProductName: String?,
    )
}

/** The receipt item built from an invoice, or why it can't be. */
sealed interface InvoiceBasket {
    data class Built(
        val item: BasketItem,
    ) : InvoiceBasket

    data class Fallback(
        val reason: String,
    ) : InvoiceBasket
}

/** Reads a Kill Bill invoice of a tenant; swapped for a stub in the service tests. Throws when it can't. */
fun interface InvoiceReader {
    fun read(
        tenantId: UUID,
        accountId: UUID,
        invoiceId: UUID,
    ): InvoiceView
}

/** [InvoiceReader] through Kill Bill's invoice API, with the tenant's context (a read). */
class KillBillInvoiceReader(
    private val killbill: OSGIKillbillAPI,
    private val now: () -> DateTime,
) : InvoiceReader {
    override fun read(
        tenantId: UUID,
        accountId: UUID,
        invoiceId: UUID,
    ): InvoiceView {
        val invoice = killbill.invoiceUserApi.getInvoice(invoiceId, PluginCallContext(tenantId, accountId, now()))
        return InvoiceView(
            id = invoice.id,
            accountId = invoice.accountId,
            currency = invoice.currency.name,
            items =
                invoice.invoiceItems.map { item ->
                    InvoiceView.Line(
                        id = item.id,
                        type = item.invoiceItemType,
                        amount = item.amount ?: BigDecimal.ZERO,
                        linkedItemId = item.linkedItemId,
                        description = item.description,
                        planName = item.planName,
                        prettyPlanName = item.prettyPlanName,
                        productName = item.productName,
                        prettyProductName = item.prettyProductName,
                    )
                },
        )
    }
}
