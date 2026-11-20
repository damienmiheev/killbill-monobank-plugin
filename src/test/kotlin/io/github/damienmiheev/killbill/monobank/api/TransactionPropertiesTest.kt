/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.api

import io.github.damienmiheev.killbill.monobank.db.TxRow
import io.github.damienmiheev.killbill.monobank.db.TxState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.killbill.billing.payment.api.TransactionType
import java.time.Instant
import java.util.UUID

/** The properties a transaction's plugin info carries (README "Plugin properties", design A6). */
class TransactionPropertiesTest {
    private fun row(
        state: TxState,
        hosted: Boolean = false,
    ): TxRow {
        val now = Instant.parse("2026-11-20T10:00:00Z")
        return TxRow(
            recordId = 1,
            tenantId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            paymentId = UUID.randomUUID(),
            paymentMethodId = UUID.randomUUID(),
            invoiceKbId = null,
            transactionId = UUID.randomUUID(),
            type = TransactionType.PURCHASE,
            reference = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            callerRef = if (hosted) "checkout-1" else null,
            invoiceId = if (hosted) "inv-1" else null,
            amountMinor = 1000,
            currency = "UAH",
            processedAmountMinor = null,
            state = state,
            kbSettledAt = null,
            monoStatus = null,
            monoModifiedAt = null,
            errorCode = null,
            errorMessage = null,
            formUrl = if (hosted) "https://pay.example/inv-1" else null,
            tdsUrl = null,
            expiresAt = if (hosted) now.plusSeconds(3600) else null,
            saveCard = false,
            basket = null,
            attemptedAt = now,
            nextCheckAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun TxRow.props(): Map<String, Any?> = transactionProperties(this).associate { it.key to it.value }

    @Test
    fun `every transaction carries the plugin's row state under state, whatever the state`() {
        for (state in TxState.entries) {
            assertThat(row(state).props()["state"]).describedAs(state.name).isEqualTo(state.name)
        }
    }

    @Test
    fun `a failed row says FAILED even though nothing else on it differs from a successful one`() {
        val failed = row(TxState.FAILED).props()
        val succeeded = row(TxState.SUCCESS).props()
        assertThat(failed - "state").isEqualTo(succeeded - "state")
        assertThat(failed["state"]).isEqualTo("FAILED")
        assertThat(succeeded["state"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `a hosted page keeps its other properties next to the state`() {
        assertThat(row(TxState.PENDING, hosted = true).props()).containsOnlyKeys(
            "formUrl",
            "invoiceId",
            "expiresAt",
            "callerRef",
            "reference",
            "state",
        )
    }
}
