/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.db

import io.github.damienmiheev.killbill.monobank.db.Db.Companion.instant
import io.github.damienmiheev.killbill.monobank.db.Db.Companion.uuid
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

enum class CardStatus { NONE, PENDING, ACTIVE, FAILED, DELETED }

data class PmRow(
    val recordId: Long,
    val tenantId: UUID,
    val accountId: UUID,
    val paymentMethodId: UUID,
    val walletId: String,
    val tokenCiphertext: ByteArray?,
    val tokenKeyId: String?,
    val tokenHmac: ByteArray?,
    val cardStatus: CardStatus,
    val sourceInvoiceId: String?,
    val maskedPan: String?,
    val cardBrand: String?,
    val cardCountry: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun equals(other: Any?): Boolean =
        other is PmRow && other.recordId == recordId && other.updatedAt == updatedAt

    override fun hashCode(): Int = recordId.hashCode()

    override fun toString(): String =
        "PmRow(paymentMethodId=$paymentMethodId, cardStatus=$cardStatus, maskedPan=$maskedPan, sourceInvoiceId=$sourceInvoiceId)"
}

class PaymentMethodDao(
    private val db: Db,
    private val now: () -> Instant,
) {
    /** Adds the row for a new Kill Bill payment method; a repeated add of the same id is a no-op. */
    fun add(
        tenantId: UUID,
        accountId: UUID,
        paymentMethodId: UUID,
    ): PmRow {
        val at = now()
        try {
            db.update(
                """
                insert into monobank_payment_methods (kb_tenant_id, kb_account_id, kb_payment_method_id, wallet_id,
                    card_status, created_at, updated_at)
                values (?, ?, ?, ?, 'NONE', ?, ?)
                """.trimIndent(),
                tenantId,
                accountId,
                paymentMethodId,
                accountId.toString(),
                at,
                at,
            )
        } catch (e: SQLException) {
            if (Db.violatedConstraint(e) != "monobank_payment_methods_kb_payment_method_id_uq") throw e
        }
        return find(paymentMethodId)!!
    }

    fun find(paymentMethodId: UUID): PmRow? =
        db
            .query(
                "select * from monobank_payment_methods where kb_payment_method_id = ?",
                paymentMethodId,
                map = ::mapRow,
            ).singleOrNull()

    fun findByAccount(accountId: UUID): List<PmRow> =
        db.query(
            "select * from monobank_payment_methods where kb_account_id = ? order by record_id",
            accountId,
            map = ::mapRow,
        )

    fun findBySourceInvoice(invoiceId: String): List<PmRow> =
        db.query("select * from monobank_payment_methods where source_invoice_id = ?", invoiceId, map = ::mapRow)

    /** Marks the card as being saved by a hosted payment on [invoiceId]; an active card is left as it is. */
    fun markPending(
        paymentMethodId: UUID,
        invoiceId: String,
    ) {
        db.update(
            """
            update monobank_payment_methods set card_status = 'PENDING', source_invoice_id = ?, updated_at = ?
            where kb_payment_method_id = ? and card_status in ('NONE', 'FAILED', 'PENDING')
            """.trimIndent(),
            invoiceId,
            now(),
            paymentMethodId,
        )
    }

    /** Stores a saved card. Only from monobank's own answer for the invoice that saved it. */
    fun activate(
        paymentMethodId: UUID,
        ciphertext: ByteArray,
        keyId: String,
        hmac: ByteArray,
        maskedPan: String?,
        brand: String?,
        country: String?,
    ): Boolean =
        db.update(
            """
            update monobank_payment_methods set card_status = 'ACTIVE', card_token_ciphertext = ?, card_token_key_id = ?,
                card_token_hmac = ?, masked_pan = coalesce(?, masked_pan), card_brand = coalesce(?, card_brand),
                card_country = coalesce(?, card_country), updated_at = ?
            where kb_payment_method_id = ? and card_status in ('NONE', 'PENDING', 'FAILED')
            """.trimIndent(),
            ciphertext,
            keyId,
            hmac,
            maskedPan,
            brand,
            country,
            now(),
            paymentMethodId,
        ) == 1

    fun setStatus(
        paymentMethodId: UUID,
        status: CardStatus,
        from: Set<CardStatus>,
    ): Boolean =
        db.update(
            "update monobank_payment_methods set card_status = ?, updated_at = ? " +
                "where kb_payment_method_id = ? and card_status = any (?)",
            status,
            now(),
            paymentMethodId,
            Db.TextArray(from.map { it.name }.toTypedArray()),
        ) == 1

    /** Whether another live payment method holds the same card (so its token must not be deleted at monobank). */
    fun sameCardElsewhere(row: PmRow): Boolean =
        row.tokenHmac != null &&
            db
                .query(
                    "select 1 from monobank_payment_methods where card_token_hmac = ? and kb_payment_method_id <> ? " +
                        "and card_status <> 'DELETED' limit 1",
                    row.tokenHmac,
                    row.paymentMethodId,
                ) { true }
                .isNotEmpty()

    /** Pending cards whose saving invoice should be polled (their own webhook may have been lost). */
    fun pendingCards(
        olderThan: Instant,
        limit: Int,
    ): List<PmRow> =
        db.query(
            "select * from monobank_payment_methods where card_status = 'PENDING' and source_invoice_id is not null " +
                "and updated_at <= ? order by updated_at limit ?",
            olderThan,
            limit,
            map = ::mapRow,
        )

    /** Rows deleted locally whose token may still be at monobank. */
    fun deletedWithToken(limit: Int): List<PmRow> =
        db.query(
            "select * from monobank_payment_methods " +
                "where card_status = 'DELETED' and card_token_ciphertext is not null " +
                "order by updated_at limit ?",
            limit,
            map = ::mapRow,
        )

    /** Forgets the token once monobank has removed the card (or must not, because another method shares it). */
    fun forgetToken(paymentMethodId: UUID) {
        db.update(
            "update monobank_payment_methods " +
                "set card_token_ciphertext = null, card_token_key_id = null, card_token_hmac = null, " +
                "updated_at = ? where kb_payment_method_id = ? and card_status = 'DELETED'",
            now(),
            paymentMethodId,
        )
    }

    companion object {
        fun mapRow(rs: ResultSet): PmRow =
            PmRow(
                recordId = rs.getLong("record_id"),
                tenantId = rs.uuid("kb_tenant_id")!!,
                accountId = rs.uuid("kb_account_id")!!,
                paymentMethodId = rs.uuid("kb_payment_method_id")!!,
                walletId = rs.getString("wallet_id"),
                tokenCiphertext = rs.getBytes("card_token_ciphertext"),
                tokenKeyId = rs.getString("card_token_key_id"),
                tokenHmac = rs.getBytes("card_token_hmac"),
                cardStatus = CardStatus.valueOf(rs.getString("card_status")),
                sourceInvoiceId = rs.getString("source_invoice_id"),
                maskedPan = rs.getString("masked_pan"),
                cardBrand = rs.getString("card_brand"),
                cardCountry = rs.getString("card_country"),
                createdAt = rs.instant("created_at")!!,
                updatedAt = rs.instant("updated_at")!!,
            )
    }
}

/** Every verified webhook, deduplicated by the hash of its exact bytes (design A3, A7). */
class NotificationDao(
    private val db: Db,
    private val now: () -> Instant,
) {
    /** Records the webhook; false when the same bytes were seen before. */
    fun record(
        bodySha256: ByteArray,
        tenantId: UUID?,
        reference: UUID?,
        invoiceId: String?,
        monoStatus: String?,
        monoModifiedAt: Instant?,
        outcome: String,
    ): Boolean =
        try {
            db.update(
                """
                insert into monobank_notifications (body_sha256, kb_tenant_id, reference, invoice_id, mono_status,
                    mono_modified_at, received_at, outcome)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                bodySha256,
                tenantId,
                reference,
                invoiceId,
                monoStatus,
                monoModifiedAt,
                now(),
                outcome,
            )
            true
        } catch (e: SQLException) {
            if (Db.violatedConstraint(e) != "monobank_notifications_body_sha256_uq") throw e
            false
        }

    fun prune(before: Instant): Int = db.update("delete from monobank_notifications where received_at < ?", before)
}
