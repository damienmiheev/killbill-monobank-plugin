/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.db

import io.github.damienmiheev.killbill.monobank.db.Db.Companion.instant
import io.github.damienmiheev.killbill.monobank.db.Db.Companion.longOrNull
import io.github.damienmiheev.killbill.monobank.db.Db.Companion.uuid
import org.killbill.billing.payment.api.TransactionType
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** The plugin's state of one Kill Bill transaction (design A4). Moves only forward. */
enum class TxState(
    /** What Kill Bill is told. */
    val pluginStatus: PaymentPluginStatus,
    val isFinal: Boolean,
) {
    SENDING(PaymentPluginStatus.UNDEFINED, false),
    PENDING(PaymentPluginStatus.PENDING, false),
    UNKNOWN(PaymentPluginStatus.UNDEFINED, false),
    REVIEW(PaymentPluginStatus.UNDEFINED, false),
    SUCCESS(PaymentPluginStatus.PROCESSED, true),
    FAILED(PaymentPluginStatus.ERROR, true),
    CANCELED(PaymentPluginStatus.CANCELED, true),
    ;

    companion object {
        /** States a row may leave. REVIEW only changes by an operator, CANCELED and the final states never. */
        val OPEN: Set<TxState> = setOf(SENDING, PENDING, UNKNOWN)
    }
}

data class TxRow(
    val recordId: Long,
    val tenantId: UUID,
    val accountId: UUID,
    val paymentId: UUID,
    val paymentMethodId: UUID?,
    val invoiceKbId: UUID?,
    val transactionId: UUID,
    val type: TransactionType,
    val reference: UUID,
    val callerRef: String?,
    val invoiceId: String?,
    val amountMinor: Long,
    val currency: String,
    val processedAmountMinor: Long?,
    val state: TxState,
    val kbSettledAt: Instant?,
    val monoStatus: String?,
    val monoModifiedAt: Instant?,
    val errorCode: String?,
    val errorMessage: String?,
    val formUrl: String?,
    val tdsUrl: String?,
    val expiresAt: Instant?,
    val saveCard: Boolean,
    val basket: String?,
    val attemptedAt: Instant?,
    val nextCheckAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** What a claim of a new transaction ran into (design A4 steps 1–2). */
sealed interface Claim {
    data class Claimed(
        val row: TxRow,
    ) : Claim

    /** A row for this Kill Bill transaction already exists: never call monobank twice for it. */
    data class Existing(
        val row: TxRow,
    ) : Claim

    /** The account (or, for refunds, the invoice) has an unresolved operation. */
    data object Unresolved : Claim
}

/** A new row, as the claim inserts it. */
data class NewTx(
    val tenantId: UUID,
    val accountId: UUID,
    val paymentId: UUID,
    val paymentMethodId: UUID?,
    val invoiceKbId: UUID?,
    val transactionId: UUID,
    val type: TransactionType,
    val callerRef: String?,
    val invoiceId: String?,
    val amountMinor: Long,
    val currency: String,
    val saveCard: Boolean,
    /** The receipt items sent with a charge (JSON), or null when none were sent. */
    val basket: String? = null,
)

/** One change to a row, applied only if the row is still in one of the expected states. */
data class TxChange(
    val state: TxState,
    val invoiceId: String? = null,
    val monoStatus: String? = null,
    val monoModifiedAt: Instant? = null,
    val processedAmountMinor: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val formUrl: String? = null,
    val tdsUrl: String? = null,
    val expiresAt: Instant? = null,
    val nextCheckAt: Instant? = null,
    /** Kill Bill already knows this final state (it was the synchronous answer). */
    val settled: Boolean = false,
)

class TransactionDao(
    private val db: Db,
    private val now: () -> Instant,
) {
    /**
     * Inserts the row as SENDING with a fresh reference. The partial unique indexes make this the guard: a second
     * unresolved charge on the account, or refund on the invoice, cannot be inserted, even from another node.
     */
    fun claim(tx: NewTx): Claim {
        findByTransaction(tx.transactionId)?.let { return Claim.Existing(it) }
        val at = now()
        return try {
            val row =
                db
                    .query(
                        """
                        insert into monobank_transactions (kb_tenant_id, kb_account_id, kb_payment_id, kb_payment_method_id,
                            kb_invoice_id, kb_transaction_id, transaction_type, reference, caller_ref, invoice_id,
                            amount_minor, currency, state, save_card, basket, attempted_at, next_check_at, created_at,
                            updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SENDING', ?, ?, ?, ?, ?, ?)
                        returning *
                        """.trimIndent(),
                        tx.tenantId,
                        tx.accountId,
                        tx.paymentId,
                        tx.paymentMethodId,
                        tx.invoiceKbId,
                        tx.transactionId,
                        tx.type,
                        UUID.randomUUID(),
                        tx.callerRef,
                        tx.invoiceId,
                        tx.amountMinor,
                        tx.currency,
                        tx.saveCard,
                        tx.basket,
                        at,
                        at,
                        at,
                        at,
                        map = ::mapRow,
                    ).single()
            Claim.Claimed(row)
        } catch (e: SQLException) {
            // Locale-independent: a unique violation is either this transaction claimed meanwhile, or a guard index.
            if (!Db.isUniqueViolation(e)) throw e
            findByTransaction(tx.transactionId)?.let { Claim.Existing(it) } ?: Claim.Unresolved
        }
    }

    /**
     * Applies [change] if the row is still in one of [from]. Returns the row as it is afterwards (changed or not),
     * so callers always answer with the stored state, never with what they hoped to write.
     */
    fun transition(
        recordId: Long,
        from: Set<TxState>,
        change: TxChange,
    ): TxRow {
        val at = now()
        db.update(
            """
            update monobank_transactions set
                state = ?,
                invoice_id = coalesce(?, invoice_id),
                mono_status = coalesce(?, mono_status),
                mono_modified_at = coalesce(?, mono_modified_at),
                processed_amount_minor = coalesce(?, processed_amount_minor),
                error_code = case when ? = 'SUCCESS' then null else coalesce(?, error_code) end,
                error_message = case when ? = 'SUCCESS' then null else coalesce(?, error_message) end,
                form_url = coalesce(?, form_url),
                tds_url = coalesce(?, tds_url),
                expires_at = coalesce(?, expires_at),
                next_check_at = ?,
                kb_settled_at = case when ? then ? else kb_settled_at end,
                updated_at = ?
            where record_id = ? and state = any (?)
              and (cast(? as timestamptz) is null or mono_modified_at is null
                   or date_trunc('second', mono_modified_at) <= cast(? as timestamptz))
            """.trimIndent(),
            change.state,
            change.invoiceId,
            change.monoStatus,
            change.monoModifiedAt?.let(::toSecond),
            change.processedAmountMinor,
            change.state,
            change.errorCode?.take(MAX_TEXT),
            change.state,
            change.errorMessage?.take(MAX_TEXT),
            change.formUrl,
            change.tdsUrl,
            change.expiresAt,
            if (change.state.isFinal) null else (change.nextCheckAt ?: at),
            change.settled,
            at,
            at,
            recordId,
            Db.TextArray(from.map { it.name }.toTypedArray()),
            change.monoModifiedAt?.let(::toSecond),
            change.monoModifiedAt?.let(::toSecond),
        )
        return findByRecord(recordId)!!
    }

    fun markSettled(recordId: Long) {
        db.update(
            "update monobank_transactions set kb_settled_at = ?, updated_at = ? where record_id = ?",
            now(),
            now(),
            recordId,
        )
    }

    fun reschedule(
        recordId: Long,
        nextCheckAt: Instant,
    ) {
        db.update("update monobank_transactions set next_check_at = ? where record_id = ?", nextCheckAt, recordId)
    }

    fun findByTransaction(transactionId: UUID): TxRow? =
        db
            .query(
                "select * from monobank_transactions where kb_transaction_id = ?",
                transactionId,
                map = ::mapRow,
            ).singleOrNull()

    fun findByRecord(recordId: Long): TxRow? =
        db.query("select * from monobank_transactions where record_id = ?", recordId, map = ::mapRow).singleOrNull()

    fun findByReference(reference: UUID): TxRow? =
        db.query("select * from monobank_transactions where reference = ?", reference, map = ::mapRow).singleOrNull()

    fun findByPayment(paymentId: UUID): List<TxRow> =
        db.query(
            "select * from monobank_transactions where kb_payment_id = ? order by record_id",
            paymentId,
            map = ::mapRow,
        )

    fun findByInvoice(invoiceId: String): List<TxRow> =
        db.query(
            "select * from monobank_transactions where invoice_id = ? order by record_id",
            invoiceId,
            map = ::mapRow,
        )

    /** Open rows due for a check, oldest first. */
    fun dueOpen(
        limit: Int,
        olderThan: Instant,
    ): List<TxRow> =
        db.query(
            """
            select * from monobank_transactions
            where state in ('SENDING', 'PENDING', 'UNKNOWN') and next_check_at <= ? and updated_at <= ?
            order by next_check_at limit ?
            """.trimIndent(),
            now(),
            olderThan,
            limit,
            map = ::mapRow,
        )

    /** Final rows Kill Bill has not been brought in line with. */
    fun unsettled(limit: Int): List<TxRow> =
        db.query(
            "select * from monobank_transactions where state in ('SUCCESS', 'FAILED') and kb_settled_at is null " +
                "and (next_settle_at is null or next_settle_at <= ?) " +
                "order by next_settle_at nulls first, record_id limit ?",
            now(),
            limit,
            map = ::mapRow,
        )

    /** Leaves a row Kill Bill could not be brought in line with for later, so it does not block the queue. */
    fun deferSettle(
        recordId: Long,
        until: Instant,
    ) {
        db.update("update monobank_transactions set next_settle_at = ? where record_id = ?", until, recordId)
    }

    fun review(limit: Int): List<TxRow> =
        db.query(
            "select * from monobank_transactions where state = 'REVIEW' order by record_id limit ?",
            limit,
            map = ::mapRow,
        )

    /** Holds that are neither captured nor voided and older than [olderThan] (monobank releases them at 9 days). */
    fun holdsOlderThan(olderThan: Instant): Long =
        db
            .query(
                """
                select count(*) from monobank_transactions a
                where a.transaction_type = 'AUTHORIZE' and a.state = 'SUCCESS' and coalesce(a.mono_modified_at, a.created_at) < ?
                  and not exists (select 1 from monobank_transactions f where f.kb_payment_id = a.kb_payment_id
                                  and f.transaction_type in ('CAPTURE', 'VOID') and f.state not in ('FAILED', 'CANCELED'))
                """.trimIndent(),
                olderThan,
            ) { it.getLong(1) }
            .single()

    fun countOpen(): Map<TxState, Long> =
        db
            .query(
                "select state, count(*) from monobank_transactions " +
                    "where state in ('PENDING', 'UNKNOWN', 'REVIEW', 'SENDING') group by state",
            ) {
                TxState.valueOf(it.getString(1)) to it.getLong(2)
            }.toMap()

    companion object {
        private const val MAX_TEXT = 2000

        fun mapRow(rs: ResultSet): TxRow =
            TxRow(
                recordId = rs.getLong("record_id"),
                tenantId = rs.uuid("kb_tenant_id")!!,
                accountId = rs.uuid("kb_account_id")!!,
                paymentId = rs.uuid("kb_payment_id")!!,
                paymentMethodId = rs.uuid("kb_payment_method_id"),
                invoiceKbId = rs.uuid("kb_invoice_id"),
                transactionId = rs.uuid("kb_transaction_id")!!,
                type = TransactionType.valueOf(rs.getString("transaction_type")),
                reference = rs.uuid("reference")!!,
                callerRef = rs.getString("caller_ref"),
                invoiceId = rs.getString("invoice_id"),
                amountMinor = rs.getLong("amount_minor"),
                currency = rs.getString("currency"),
                processedAmountMinor = rs.longOrNull("processed_amount_minor"),
                state = TxState.valueOf(rs.getString("state")),
                kbSettledAt = rs.instant("kb_settled_at"),
                monoStatus = rs.getString("mono_status"),
                monoModifiedAt = rs.instant("mono_modified_at"),
                errorCode = rs.getString("error_code"),
                errorMessage = rs.getString("error_message"),
                formUrl = rs.getString("form_url"),
                tdsUrl = rs.getString("tds_url"),
                expiresAt = rs.instant("expires_at"),
                saveCard = rs.getBoolean("save_card"),
                basket = rs.getString("basket"),
                attemptedAt = rs.instant("attempted_at"),
                nextCheckAt = rs.instant("next_check_at"),
                createdAt = rs.instant("created_at")!!,
                updatedAt = rs.instant("updated_at")!!,
            )
    }
}

/**
 * monobank's times compared and stored to the second: invoice/status gives whole seconds while other answers carry
 * fractions, so a newer status at 19:04:33 must not lose to an older answer at 19:04:33.525 (seen live: a saved-card
 * hold stuck PENDING although monobank said `hold`). Final states stay protected by the state rule.
 */
private fun toSecond(instant: Instant): Instant = instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
