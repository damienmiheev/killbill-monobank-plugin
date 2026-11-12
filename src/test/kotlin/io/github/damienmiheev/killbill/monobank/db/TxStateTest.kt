/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus

/**
 * What Kill Bill is told for each state (design A6). Kill Bill maps PROCESSED to SUCCESS, ERROR to PAYMENT_FAILURE,
 * CANCELED to PLUGIN_FAILURE (retryable, nothing reached monobank) and UNDEFINED to UNKNOWN (its Janitor asks again).
 */
class TxStateTest {
    @Test
    fun `only a state monobank settled is final in Kill Bill`() {
        assertThat(TxState.SUCCESS.pluginStatus).isEqualTo(PaymentPluginStatus.PROCESSED)
        assertThat(TxState.FAILED.pluginStatus).isEqualTo(PaymentPluginStatus.ERROR)
        assertThat(TxState.CANCELED.pluginStatus).isEqualTo(PaymentPluginStatus.CANCELED)
        assertThat(TxState.PENDING.pluginStatus).isEqualTo(PaymentPluginStatus.PENDING)
    }

    @Test
    fun `a call that may have reached monobank is never reported as failed`() {
        for (state in listOf(TxState.SENDING, TxState.UNKNOWN, TxState.REVIEW)) {
            assertThat(state.pluginStatus).describedAs(state.name).isEqualTo(PaymentPluginStatus.UNDEFINED)
            assertThat(state.isFinal).describedAs(state.name).isFalse()
        }
    }

    @Test
    fun `only open states move on their own, review waits for an operator`() {
        assertThat(TxState.OPEN).containsExactlyInAnyOrder(TxState.SENDING, TxState.PENDING, TxState.UNKNOWN)
        assertThat(TxState.entries.filter { it.isFinal })
            .containsExactlyInAnyOrder(TxState.SUCCESS, TxState.FAILED, TxState.CANCELED)
        assertThat(TxState.OPEN.none { it.isFinal }).isTrue()
        assertThat(TxState.REVIEW).isNotIn(TxState.OPEN)
    }
}
