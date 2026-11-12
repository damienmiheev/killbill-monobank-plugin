/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.api

import org.joda.time.DateTime
import org.killbill.billing.catalog.api.Currency
import org.killbill.billing.payment.api.PaymentMethodPlugin
import org.killbill.billing.payment.api.PluginProperty
import org.killbill.billing.payment.api.TransactionType
import org.killbill.billing.payment.plugin.api.GatewayNotification
import org.killbill.billing.payment.plugin.api.HostedPaymentPageFormDescriptor
import org.killbill.billing.payment.plugin.api.PaymentMethodInfoPlugin
import org.killbill.billing.payment.plugin.api.PaymentPluginApi
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin
import org.killbill.billing.util.callcontext.CallContext
import org.killbill.billing.util.callcontext.TenantContext
import org.killbill.billing.util.entity.Pagination
import org.killbill.clock.Clock
import java.math.BigDecimal
import java.util.UUID

/**
 * The Kill Bill payment API of the plugin.
 *
 * This version is the skeleton: the bundle loads, checks its schema and settings and reports health.
 * Every money-moving call is refused as CANCELED (PLUGIN_FAILURE) with code `NOT_IMPLEMENTED`, so nothing
 * can be charged by accident; the following versions implement the operations one by one.
 */
class MonobankPaymentPluginApi(
    private val clock: Clock,
) : PaymentPluginApi {
    override fun authorizePayment(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        kbPaymentMethodId: UUID?,
        amount: BigDecimal?,
        currency: Currency?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): PaymentTransactionInfoPlugin =
        notImplemented(kbPaymentId, kbTransactionId, TransactionType.AUTHORIZE, amount, currency)

    override fun capturePayment(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        kbPaymentMethodId: UUID?,
        amount: BigDecimal?,
        currency: Currency?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): PaymentTransactionInfoPlugin =
        notImplemented(kbPaymentId, kbTransactionId, TransactionType.CAPTURE, amount, currency)

    override fun purchasePayment(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        kbPaymentMethodId: UUID?,
        amount: BigDecimal?,
        currency: Currency?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): PaymentTransactionInfoPlugin =
        notImplemented(kbPaymentId, kbTransactionId, TransactionType.PURCHASE, amount, currency)

    override fun voidPayment(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        kbPaymentMethodId: UUID?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): PaymentTransactionInfoPlugin = notImplemented(kbPaymentId, kbTransactionId, TransactionType.VOID, null, null)

    override fun creditPayment(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        kbPaymentMethodId: UUID?,
        amount: BigDecimal?,
        currency: Currency?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): PaymentTransactionInfoPlugin =
        TransactionResult(
            kbPaymentId,
            kbTransactionId,
            TransactionType.CREDIT,
            amount,
            currency,
            PaymentPluginStatus.CANCELED,
            "UNSUPPORTED",
            "monobank does not support credits",
            clock.utcNow,
        )

    override fun refundPayment(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        kbPaymentMethodId: UUID?,
        amount: BigDecimal?,
        currency: Currency?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): PaymentTransactionInfoPlugin =
        notImplemented(kbPaymentId, kbTransactionId, TransactionType.REFUND, amount, currency)

    override fun getPaymentInfo(
        kbAccountId: UUID?,
        kbPaymentId: UUID?,
        properties: Iterable<PluginProperty>?,
        context: TenantContext?,
    ): List<PaymentTransactionInfoPlugin> = emptyList()

    override fun searchPayments(
        searchKey: String?,
        offset: Long?,
        limit: Long?,
        properties: Iterable<PluginProperty>?,
        context: TenantContext?,
    ): Pagination<PaymentTransactionInfoPlugin> = EmptyPagination(offset)

    override fun addPaymentMethod(
        kbAccountId: UUID?,
        kbPaymentMethodId: UUID?,
        paymentMethodProps: PaymentMethodPlugin?,
        setDefault: Boolean,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): Unit = throw PaymentPluginApiException(NOT_IMPLEMENTED, "payment methods are not supported by this version")

    override fun deletePaymentMethod(
        kbAccountId: UUID?,
        kbPaymentMethodId: UUID?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): Unit = throw PaymentPluginApiException(NOT_IMPLEMENTED, "payment methods are not supported by this version")

    override fun getPaymentMethodDetail(
        kbAccountId: UUID?,
        kbPaymentMethodId: UUID?,
        properties: Iterable<PluginProperty>?,
        context: TenantContext?,
    ): PaymentMethodPlugin =
        throw PaymentPluginApiException(NOT_IMPLEMENTED, "payment methods are not supported by this version")

    override fun setDefaultPaymentMethod(
        kbAccountId: UUID?,
        kbPaymentMethodId: UUID?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ) {
        // Kill Bill owns the default payment method; the plugin keeps no copy of it.
    }

    override fun getPaymentMethods(
        kbAccountId: UUID?,
        refreshFromGateway: Boolean,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): List<PaymentMethodInfoPlugin> = emptyList()

    override fun searchPaymentMethods(
        searchKey: String?,
        offset: Long?,
        limit: Long?,
        properties: Iterable<PluginProperty>?,
        context: TenantContext?,
    ): Pagination<PaymentMethodPlugin> = EmptyPagination(offset)

    override fun resetPaymentMethods(
        kbAccountId: UUID?,
        paymentMethods: List<PaymentMethodInfoPlugin>?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ) {
        // Nothing is imported from monobank that the plugin did not save itself.
    }

    override fun buildFormDescriptor(
        kbAccountId: UUID?,
        customFields: Iterable<PluginProperty>?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): HostedPaymentPageFormDescriptor =
        throw PaymentPluginApiException(
            UNSUPPORTED,
            "use a PURCHASE or AUTHORIZE with the plugin property hosted=true; it returns PENDING with formUrl",
        )

    override fun processNotification(
        notification: String?,
        properties: Iterable<PluginProperty>?,
        context: CallContext?,
    ): GatewayNotification =
        throw PaymentPluginApiException(
            UNSUPPORTED,
            "monobank webhooks go to POST /plugins/killbill-monobank/notification",
        )

    private fun notImplemented(
        kbPaymentId: UUID?,
        kbTransactionId: UUID?,
        type: TransactionType,
        amount: BigDecimal?,
        currency: Currency?,
    ): PaymentTransactionInfoPlugin =
        TransactionResult(
            kbPaymentId,
            kbTransactionId,
            type,
            amount,
            currency,
            PaymentPluginStatus.CANCELED,
            NOT_IMPLEMENTED,
            "$type is not implemented in this version of the plugin",
            clock.utcNow,
        )

    companion object {
        const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
        const val UNSUPPORTED = "UNSUPPORTED"
    }
}

/** A transaction result as Kill Bill reads it. */
class TransactionResult(
    private val kbPaymentId: UUID?,
    private val kbTransactionId: UUID?,
    private val type: TransactionType,
    private val amount: BigDecimal?,
    private val currency: Currency?,
    private val status: PaymentPluginStatus,
    private val errorCode: String?,
    private val error: String?,
    private val at: DateTime,
    private val firstReference: String? = null,
    private val secondReference: String? = null,
    private val properties: List<PluginProperty> = emptyList(),
) : PaymentTransactionInfoPlugin {
    override fun getKbPaymentId(): UUID? = kbPaymentId

    override fun getKbTransactionPaymentId(): UUID? = kbTransactionId

    override fun getTransactionType(): TransactionType = type

    override fun getAmount(): BigDecimal? = amount

    override fun getCurrency(): Currency? = currency

    override fun getCreatedDate(): DateTime = at

    override fun getEffectiveDate(): DateTime = at

    override fun getStatus(): PaymentPluginStatus = status

    override fun getGatewayError(): String? = error

    override fun getGatewayErrorCode(): String? = errorCode

    override fun getFirstPaymentReferenceId(): String? = firstReference

    override fun getSecondPaymentReferenceId(): String? = secondReference

    override fun getProperties(): List<PluginProperty> = properties
}

private class EmptyPagination<T>(
    private val offset: Long?,
) : Pagination<T> {
    override fun getCurrentOffset(): Long = offset ?: 0L

    override fun getNextOffset(): Long? = null

    override fun getMaxNbRecords(): Long = 0L

    override fun getTotalNbRecords(): Long = 0L

    override fun iterator(): MutableIterator<T> = mutableListOf<T>().iterator()

    override fun close() = Unit
}
