/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.db

import javax.sql.DataSource

/**
 * Checks that the operator applied the plugin's schema. The plugin never creates or alters tables:
 * a missing table or column stops the plugin at start with a message naming what is missing.
 */
object SchemaCheck {
    /** Every table and the columns the code relies on. Kept in step with ddl-postgresql.sql by a test. */
    val REQUIRED: Map<String, Set<String>> =
        mapOf(
            "monobank_transactions" to
                setOf(
                    "record_id",
                    "kb_tenant_id",
                    "kb_account_id",
                    "kb_payment_id",
                    "kb_payment_method_id",
                    "kb_invoice_id",
                    "kb_transaction_id",
                    "transaction_type",
                    "reference",
                    "caller_ref",
                    "invoice_id",
                    "amount_minor",
                    "currency",
                    "processed_amount_minor",
                    "state",
                    "kb_settled_at",
                    "mono_status",
                    "mono_modified_at",
                    "error_code",
                    "error_message",
                    "form_url",
                    "tds_url",
                    "expires_at",
                    "save_card",
                    "basket",
                    "attempted_at",
                    "next_check_at",
                    "next_settle_at",
                    "created_at",
                    "updated_at",
                ),
            "monobank_payment_methods" to
                setOf(
                    "record_id",
                    "kb_tenant_id",
                    "kb_account_id",
                    "kb_payment_method_id",
                    "wallet_id",
                    "card_token_ciphertext",
                    "card_token_key_id",
                    "card_token_hmac",
                    "card_status",
                    "source_invoice_id",
                    "masked_pan",
                    "card_brand",
                    "card_country",
                    "created_at",
                    "updated_at",
                ),
            "monobank_notifications" to
                setOf(
                    "record_id",
                    "body_sha256",
                    "kb_tenant_id",
                    "reference",
                    "invoice_id",
                    "mono_status",
                    "mono_modified_at",
                    "received_at",
                    "outcome",
                ),
        )

    /**
     * The unique indexes money safety depends on: idempotency per Kill Bill transaction, one row per reference and
     * per charge invoice, and the guards against a second charge or refund while one is unresolved.
     */
    val REQUIRED_UNIQUE_INDEXES: Set<String> =
        setOf(
            "monobank_transactions_kb_transaction_id_uq",
            "monobank_transactions_reference_uq",
            "monobank_transactions_charge_invoice_uq",
            "monobank_transactions_unresolved_charge_uq",
            "monobank_transactions_unresolved_refund_uq",
            "monobank_transactions_capture_void_uq",
            "monobank_transactions_open_invoice_charge_uq",
            "monobank_payment_methods_kb_payment_method_id_uq",
            "monobank_notifications_body_sha256_uq",
        )

    /** The missing tables, columns and unique indexes, as readable messages; empty when the schema is complete. */
    fun missing(dataSource: DataSource): List<String> {
        val present = mutableMapOf<String, MutableSet<String>>()
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    select table_name, column_name from information_schema.columns
                    where table_schema = current_schema() and table_name = any (?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setArray(1, conn.createArrayOf("varchar", REQUIRED.keys.toTypedArray()))
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            present.getOrPut(rs.getString(1)) { mutableSetOf() } += rs.getString(2)
                        }
                    }
                }
        }
        val missingColumns =
            REQUIRED.flatMap { (table, columns) ->
                val have = present[table] ?: return@flatMap listOf("table $table is missing")
                (columns - have).sorted().map { "column $table.$it is missing" }
            }
        val missingIndexes =
            (REQUIRED_UNIQUE_INDEXES - uniqueIndexes(dataSource)).sorted().map {
                "unique index $it is missing"
            }
        return missingColumns + missingIndexes
    }

    private fun uniqueIndexes(dataSource: DataSource): Set<String> {
        val names = mutableSetOf<String>()
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    select i.relname from pg_index x
                    join pg_class i on i.oid = x.indexrelid
                    join pg_namespace n on n.oid = i.relnamespace
                    where x.indisunique and n.nspname = current_schema() and i.relname like 'monobank\_%'
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) names += rs.getString(1)
                    }
                }
        }
        return names
    }

    /** Throws [SchemaMissingException] unless the schema is complete. */
    fun require(dataSource: DataSource) {
        val missing = missing(dataSource)
        if (missing.isNotEmpty()) throw SchemaMissingException(missing)
    }
}

class SchemaMissingException(
    val missing: List<String>,
) : IllegalStateException(
        "The monobank plugin's tables are missing or out of date (apply ddl-postgresql.sql and the migrations): " +
            missing.joinToString("; "),
    )
