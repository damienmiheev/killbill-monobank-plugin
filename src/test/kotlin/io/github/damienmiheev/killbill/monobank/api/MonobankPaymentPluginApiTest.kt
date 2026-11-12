/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.jupiter.api.Test
import org.killbill.billing.catalog.api.Currency
import org.killbill.billing.payment.api.TransactionType
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin
import org.killbill.clock.DefaultClock
import java.math.BigDecimal
import java.util.UUID

/** Until the operations land, nothing can move money: every money call is refused as PLUGIN_FAILURE. */
class MonobankPaymentPluginApiTest {
    private val api = MonobankPaymentPluginApi(DefaultClock())
    private val payment = UUID.randomUUID()
    private val txn = UUID.randomUUID()
    private val amount = BigDecimal("10.00")

    private fun assertRefused(
        result: PaymentTransactionInfoPlugin,
        type: TransactionType,
        code: String = MonobankPaymentPluginApi.NOT_IMPLEMENTED,
    ) {
        assertThat(result.status).isEqualTo(PaymentPluginStatus.CANCELED)
        assertThat(result.gatewayErrorCode).isEqualTo(code)
        assertThat(result.transactionType).isEqualTo(type)
        assertThat(result.kbPaymentId).isEqualTo(payment)
        assertThat(result.kbTransactionPaymentId).isEqualTo(txn)
        assertThat(result.createdDate.isAfter(DateTime.now(DateTimeZone.UTC).minusMinutes(1))).isTrue
    }

    @Test
    fun `every money operation is refused, with Kill Bill's nullable arguments`() {
        val acct = UUID.randomUUID()
        val pm = UUID.randomUUID()
        assertRefused(
            api.authorizePayment(acct, payment, txn, pm, amount, Currency.UAH, null, null),
            TransactionType.AUTHORIZE,
        )
        assertRefused(
            api.capturePayment(null, payment, txn, null, amount, null, emptyList(), null),
            TransactionType.CAPTURE,
        )
        assertRefused(
            api.purchasePayment(acct, payment, txn, pm, null, Currency.USD, null, null),
            TransactionType.PURCHASE,
        )
        assertRefused(api.voidPayment(acct, payment, txn, pm, null, null), TransactionType.VOID)
        assertRefused(
            api.refundPayment(acct, payment, txn, pm, amount, Currency.UAH, null, null),
            TransactionType.REFUND,
        )
        assertRefused(
            api.creditPayment(acct, payment, txn, pm, amount, Currency.UAH, null, null),
            TransactionType.CREDIT,
            MonobankPaymentPluginApi.UNSUPPORTED,
        )
    }

    @Test
    fun `reads are empty and payment-method writes are refused`() {
        assertThat(api.getPaymentInfo(null, payment, null, null)).isEmpty()
        assertThat(api.getPaymentMethods(null, true, null, null)).isEmpty()
        assertThat(api.searchPayments("x", null, null, null, null).iterator().hasNext()).isFalse
        assertThat(api.searchPaymentMethods("x", 0L, 10L, null, null).iterator().hasNext()).isFalse
        assertThatThrownBy { api.addPaymentMethod(null, null, null, true, null, null) }
            .isInstanceOf(PaymentPluginApiException::class.java)
        assertThatThrownBy { api.buildFormDescriptor(null, null, null, null) }
            .isInstanceOf(PaymentPluginApiException::class.java)
            .satisfies({ assertThat((it as PaymentPluginApiException).errorMessage).contains("hosted=true") })
        assertThatThrownBy { api.processNotification("{}", null, null) }
            .isInstanceOf(PaymentPluginApiException::class.java)
    }
}
