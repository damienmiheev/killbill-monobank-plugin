/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** A small JDBC helper: parameter binding, row mapping and transactions, nothing more. */
class Db(
    private val dataSource: DataSource,
) {
    fun <T> query(
        sql: String,
        vararg params: Any?,
        map: (ResultSet) -> T,
    ): List<T> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps, params)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<T>()
                    while (rs.next()) out += map(rs)
                    out
                }
            }
        }

    fun update(
        sql: String,
        vararg params: Any?,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps, params)
                ps.executeUpdate()
            }
        }

    /** Runs [block] in one database transaction, committed on success and rolled back on any exception. */
    fun <T> inTransaction(block: (Tx) -> T): T =
        dataSource.connection.use { conn ->
            val autoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(Tx(conn))
                conn.commit()
                result
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = autoCommit }
            }
        }

    /** Statements inside one transaction. */
    class Tx internal constructor(
        private val conn: Connection,
    ) {
        fun <T> query(
            sql: String,
            vararg params: Any?,
            map: (ResultSet) -> T,
        ): List<T> =
            conn.prepareStatement(sql).use { ps ->
                bind(ps, params)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<T>()
                    while (rs.next()) out += map(rs)
                    out
                }
            }

        fun update(
            sql: String,
            vararg params: Any?,
        ): Int =
            conn.prepareStatement(sql).use { ps ->
                bind(ps, params)
                ps.executeUpdate()
            }
    }

    /** A PostgreSQL `text[]` parameter, e.g. for `= any (?)`. */
    class TextArray(
        val values: Array<String>,
    )

    companion object {
        private const val UNIQUE_VIOLATION = "23505"

        internal fun bind(
            ps: PreparedStatement,
            params: Array<out Any?>,
        ) {
            params.forEachIndexed { i, value ->
                val index = i + 1
                when (value) {
                    null -> ps.setNull(index, Types.NULL)
                    is UUID -> ps.setObject(index, value)
                    is Instant -> ps.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
                    is ByteArray -> ps.setBytes(index, value)
                    is Enum<*> -> ps.setString(index, value.name)
                    is TextArray -> ps.setArray(index, ps.connection.createArrayOf("text", value.values))
                    else -> ps.setObject(index, value)
                }
            }
        }

        /** Whether [e] or a chained exception is a unique violation, by SQLSTATE (locale-independent). */
        fun isUniqueViolation(e: SQLException): Boolean {
            var current: SQLException? = e
            while (current != null) {
                if (current.sqlState == UNIQUE_VIOLATION) return true
                current = current.nextException
            }
            return false
        }

        fun ResultSet.uuid(column: String): UUID? = getObject(column) as UUID?

        fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

        fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
    }
}
