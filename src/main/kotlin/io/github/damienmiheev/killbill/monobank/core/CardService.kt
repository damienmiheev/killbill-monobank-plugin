/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import io.github.damienmiheev.killbill.monobank.config.TenantSettings
import io.github.damienmiheev.killbill.monobank.config.TenantSettingsProvider
import io.github.damienmiheev.killbill.monobank.crypto.CardTokenCipher
import io.github.damienmiheev.killbill.monobank.crypto.CardTokenUnreadableException
import io.github.damienmiheev.killbill.monobank.db.CardStatus
import io.github.damienmiheev.killbill.monobank.db.PaymentMethodDao
import io.github.damienmiheev.killbill.monobank.db.PmRow
import io.github.damienmiheev.killbill.monobank.mono.MonobankClients
import io.github.damienmiheev.monobank.core.MonobankException
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import org.slf4j.LoggerFactory
import java.util.UUID

/** Kill Bill payment methods backed by monobank saved cards (design A5 "Payment methods"). */
class CardService(
    private val paymentMethods: PaymentMethodDao,
    private val clients: MonobankClients,
    private val tenants: TenantSettingsProvider,
    private val cipher: CardTokenCipher,
) {
    /** A new payment method starts without a card: the card only ever comes from monobank's answer. */
    fun add(
        tenantId: UUID,
        accountId: UUID,
        paymentMethodId: UUID,
    ): PmRow = paymentMethods.add(tenantId, accountId, paymentMethodId)

    fun find(paymentMethodId: UUID): PmRow? = paymentMethods.find(paymentMethodId)

    fun byAccount(accountId: UUID): List<PmRow> =
        paymentMethods.findByAccount(accountId).filter {
            it.cardStatus !=
                CardStatus.DELETED
        }

    /** Marks the method deleted and removes the card at monobank, unless another live method holds the same card. */
    fun delete(paymentMethodId: UUID) {
        val row = paymentMethods.find(paymentMethodId) ?: return
        paymentMethods.setStatus(paymentMethodId, CardStatus.DELETED, CardStatus.entries.toSet() - CardStatus.DELETED)
        removeAtMonobank(paymentMethods.find(paymentMethodId) ?: row)
    }

    /** Retries removals that failed earlier (called by the reconcile job). */
    fun retryRemovals(limit: Int) {
        paymentMethods.deletedWithToken(limit).forEach(::removeAtMonobank)
    }

    private fun removeAtMonobank(row: PmRow) {
        if (row.cardStatus != CardStatus.DELETED || row.tokenCiphertext == null || row.tokenKeyId == null) return
        if (paymentMethods.sameCardElsewhere(row)) {
            paymentMethods.forgetToken(row.paymentMethodId)
            return
        }
        val settings = (tenants.get(row.tenantId) as? TenantSettings.ParseResult.Valid)?.settings ?: return
        try {
            val token =
                cipher.open(
                    CardTokenCipher.Sealed(row.tokenKeyId, row.tokenCiphertext),
                    row.tenantId,
                    row.paymentMethodId,
                )
            clients.forToken(settings.merchantToken).wallet.deleteCard(token)
            paymentMethods.forgetToken(row.paymentMethodId)
            LOG.info("Card of payment method {} removed at monobank", row.paymentMethodId)
        } catch (e: MonobankNotFoundException) {
            LOG.debug("Card of payment method {} was already gone at monobank", row.paymentMethodId, e)
            paymentMethods.forgetToken(row.paymentMethodId)
        } catch (e: MonobankException) {
            LOG.warn(
                "Could not remove the card of payment method {} at monobank ({}); will retry",
                row.paymentMethodId,
                e.outcome,
            )
        } catch (e: CardTokenUnreadableException) {
            LOG.error("Card token of deleted payment method {} cannot be read: {}", row.paymentMethodId, e.message)
            paymentMethods.forgetToken(row.paymentMethodId)
        }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(CardService::class.java)
    }
}
