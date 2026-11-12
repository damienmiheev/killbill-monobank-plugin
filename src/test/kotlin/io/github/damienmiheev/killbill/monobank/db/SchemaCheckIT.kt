/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.db

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path

/** The shipped DDL and the columns the code relies on never drift apart. */
class SchemaCheckIT {
    private fun withDatabase(block: (PGSimpleDataSource) -> Unit) {
        // The database image Kill Bill runs on, not a generic PostgreSQL.
        val image =
            org.testcontainers.utility.DockerImageName
                .parse(System.getProperty("killbill.db.image"))
                .asCompatibleSubstituteFor("postgres")
        PostgreSQLContainer(image).use { pg ->
            pg.start()
            val ds =
                PGSimpleDataSource().apply {
                    setURL(pg.jdbcUrl)
                    user = pg.username
                    password = pg.password
                }
            block(ds)
        }
    }

    private fun applyDdl(ds: PGSimpleDataSource) {
        val ddl = Files.readString(Path.of(System.getProperty("plugin.ddl")))
        ds.connection.use { c -> c.createStatement().use { it.execute(ddl) } }
    }

    @Test
    fun `the shipped DDL satisfies the check, and a dropped column or table is named`() {
        withDatabase { ds ->
            assertThat(SchemaCheck.missing(ds)).describedAs("empty database").contains(
                "table monobank_transactions is missing",
                "table monobank_payment_methods is missing",
                "table monobank_notifications is missing",
            )

            applyDdl(ds)
            assertThat(SchemaCheck.missing(ds)).isEmpty()
            SchemaCheck.require(ds)

            ds.connection.use { c ->
                c.createStatement().use {
                    it.execute("alter table monobank_transactions drop column kb_settled_at")
                    it.execute("drop table monobank_notifications")
                    it.execute("drop index monobank_transactions_unresolved_charge_uq")
                }
            }
            assertThat(SchemaCheck.missing(ds)).containsExactlyInAnyOrder(
                "column monobank_transactions.kb_settled_at is missing",
                "table monobank_notifications is missing",
                "unique index monobank_notifications_body_sha256_uq is missing",
                "unique index monobank_transactions_unresolved_charge_uq is missing",
            )
            assertThatThrownBy { SchemaCheck.require(ds) }.isInstanceOf(SchemaMissingException::class.java)
        }
    }

    @Test
    fun `the DDL declares exactly the columns the code relies on`() {
        withDatabase { ds ->
            applyDdl(ds)
            val actual = mutableMapOf<String, MutableSet<String>>()
            ds.connection.use { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "select table_name, column_name from information_schema.columns " +
                                "where table_schema = current_schema() and table_name like 'monobank\\_%'",
                        ).use { rs ->
                            while (rs.next()) actual.getOrPut(rs.getString(1)) { mutableSetOf() } += rs.getString(2)
                        }
                }
            }
            assertThat(actual).isEqualTo(SchemaCheck.REQUIRED)
        }
    }

    @Test
    fun `the database itself refuses a second unresolved charge on an account`() {
        withDatabase { ds ->
            applyDdl(ds)
            val account = java.util.UUID.randomUUID()

            fun insert(state: String) =
                ds.connection.use { c ->
                    c
                        .prepareStatement(
                            """
                            insert into monobank_transactions (kb_tenant_id, kb_account_id, kb_payment_id,
                                kb_transaction_id, transaction_type, reference, amount_minor, currency, state,
                                created_at, updated_at)
                            values (gen_random_uuid(), ?, gen_random_uuid(), gen_random_uuid(), 'PURCHASE',
                                gen_random_uuid(), 100, 'UAH', ?, now(), now())
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setObject(1, account)
                            ps.setString(2, state)
                            ps.executeUpdate()
                        }
                }

            insert("SUCCESS")
            insert("SENDING")
            assertThatThrownBy { insert("UNKNOWN") }
                .isInstanceOf(java.sql.SQLException::class.java)
                .hasMessageContaining("monobank_transactions_unresolved_charge_uq")
        }
    }
}
