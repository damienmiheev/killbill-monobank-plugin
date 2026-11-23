/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import io.github.damienmiheev.killbill.monobank.config.TenantSettings
import io.github.damienmiheev.killbill.monobank.money.Money
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

/** The receipt item of a charge that pays a Kill Bill invoice (design A5, "Receipt items from the invoice"). */
object InvoiceBaskets {
    /**
     * The one item of a charge that pays [invoice] (design A5, "Receipt items from the invoice"): named after what the
     * invoice charges, quantity 1, priced at the charge's amount, with the tenant's code and split receiver. A
     * [InvoiceBasket.Fallback] says why it can't be built (the caller then sends the default item).
     */
    fun build(
        invoice: InvoiceView,
        settings: TenantSettings,
        accountId: java.util.UUID,
        money: Money,
    ): InvoiceBasket {
        val code = settings.basketItemCode ?: return InvoiceBasket.Fallback("the tenant sends no basket")
        if (invoice.accountId != accountId) return InvoiceBasket.Fallback("the invoice belongs to another account")
        if (invoice.currency != money.currency.currencyCode) {
            return InvoiceBasket.Fallback("the invoice's currency is ${invoice.currency}")
        }
        if (money.minor <= 0) return InvoiceBasket.Fallback("nothing to charge")
        val onInvoice = invoice.items.map { it.id }.toSet()
        // adjustments on this invoice of its own items; one linked to another invoice's item names nothing
        val adjusted =
            invoice.items
                .filter { it.type in ADJUSTMENTS && it.linkedItemId != null && it.linkedItemId in onInvoice }
                .groupBy({ it.linkedItemId!! }, { it.amount })
                .mapValues { (_, amounts) -> amounts.fold(BigDecimal.ZERO, BigDecimal::add) }
        val lines =
            invoice.items.filter { line ->
                line.type in CHARGES && (line.amount + (adjusted[line.id] ?: BigDecimal.ZERO)).signum() > 0
            }
        if (lines.isEmpty()) return InvoiceBasket.Fallback("the invoice has no charge line")
        val names = lines.mapNotNull { ItemNames.clean(nameOf(it)) }.distinct()
        if (names.isEmpty()) return InvoiceBasket.Fallback("the invoice's charge lines have no name")
        return InvoiceBasket.Built(
            BasketItem
                .builder(ItemNames.cut(names.joinToString("; ")), BigDecimal.ONE, money.minor, code)
                .total(money.minor)
                .splitReceiverId(settings.splitReceiverId)
                .build(),
        )
    }

    private fun nameOf(line: InvoiceView.Line): String? =
        if (line.type == InvoiceItemType.EXTERNAL_CHARGE) {
            line.description?.takeIf { it.isNotBlank() } ?: line.prettyProductName ?: line.productName
        } else {
            line.prettyPlanName?.takeIf { it.isNotBlank() } ?: line.planName
        }

    private val CHARGES =
        setOf(
            InvoiceItemType.RECURRING,
            InvoiceItemType.FIXED,
            InvoiceItemType.USAGE,
            InvoiceItemType.EXTERNAL_CHARGE,
        )
    private val ADJUSTMENTS = setOf(InvoiceItemType.ITEM_ADJ, InvoiceItemType.REPAIR_ADJ)
}
