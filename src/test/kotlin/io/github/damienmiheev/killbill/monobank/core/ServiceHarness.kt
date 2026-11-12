/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import com.sun.net.httpserver.HttpServer
import io.github.damienmiheev.killbill.monobank.config.SystemSettings
import io.github.damienmiheev.killbill.monobank.config.TenantSettingsProvider
import io.github.damienmiheev.killbill.monobank.crypto.CardTokenCipher
import io.github.damienmiheev.killbill.monobank.db.CardStatus
import io.github.damienmiheev.killbill.monobank.db.Db
import io.github.damienmiheev.killbill.monobank.db.NotificationDao
import io.github.damienmiheev.killbill.monobank.db.PaymentMethodDao
import io.github.damienmiheev.killbill.monobank.db.TransactionDao
import io.github.damienmiheev.killbill.monobank.db.TxRow
import io.github.damienmiheev.killbill.monobank.db.TxState
import io.github.damienmiheev.killbill.monobank.mono.MonobankClients
import io.github.damienmiheev.killbill.monobank.reconcile.ReconcileJob
import io.github.damienmiheev.monobank.testkit.FakeMonobankAcquiring
import io.github.damienmiheev.monobank.testkit.Fault
import io.github.damienmiheev.monobank.testkit.MonobankPaths
import io.github.damienmiheev.monobank.testkit.SplitReceiver
import io.github.damienmiheev.monobank.testkit.WebhookDelivery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.killbill.billing.catalog.api.Currency
import org.killbill.billing.payment.api.PluginProperty
import org.killbill.billing.payment.api.TransactionType
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The shared set-up of the service tests: the payment logic against the SDK's fake monobank on this machine and a
 * real PostgreSQL, without Kill Bill, with a clock the test controls. Each test class gets its own database and fake.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ServiceHarness {
    protected lateinit var pg: PostgreSQLContainer
    protected lateinit var fake: FakeMonobankAcquiring
    protected lateinit var receiver: HttpServer
    protected var clock = Instant.now()
    protected val tenantId = UUID.randomUUID()
    protected val settled = CopyOnWriteArrayList<TxRow>()

    protected lateinit var transactions: TransactionDao
    protected lateinit var paymentMethods: PaymentMethodDao
    protected lateinit var payments: PaymentService

    @Volatile
    protected lateinit var handler: NotificationHandler
    protected lateinit var reconcile: ReconcileJob
    protected lateinit var metrics: PluginMetrics
    protected lateinit var dataSource: PGSimpleDataSource
    protected lateinit var newReconcileJob: () -> ReconcileJob
    protected lateinit var newHandler: () -> NotificationHandler

    /** Kill Bill's side, steered per test: what settling does, and what an operator decided for a review. */
    @Volatile
    protected var settleHook: (TxRow) -> Unit = {}
    protected val reviewed = java.util.concurrent.ConcurrentHashMap<Long, TxState>()

    @BeforeAll
    fun start() {
        val image = DockerImageName.parse(System.getProperty("killbill.db.image")).asCompatibleSubstituteFor("postgres")
        pg = PostgreSQLContainer(image).also { it.start() }
        val ds =
            PGSimpleDataSource().apply {
                setURL(pg.jdbcUrl)
                user = pg.username
                password = pg.password
            }
        dataSource = ds
        ds.connection.use { c ->
            c.createStatement().use { it.execute(Files.readString(Path.of(System.getProperty("plugin.ddl")))) }
        }
        fake =
            FakeMonobankAcquiring
                .builder()
                // like monobank's test merchant: receipts and split payments on
                .fiscalization(true)
                .splitPayments(true)
                .build()
                .start()
        receiver = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        receiver.createContext("/hook") { exchange ->
            val result =
                handler.handle(
                    exchange.requestBody.readAllBytes(),
                    exchange.requestHeaders.getFirst("X-Sign"),
                    null,
                )
            exchange.sendResponseHeaders(result.httpStatus, -1)
            exchange.close()
        }
        receiver.start()

        val settings = systemSettings()
        val tenants = tenants()
        val now = { clock }
        val db = Db(ds)
        transactions = TransactionDao(db, now)
        paymentMethods = PaymentMethodDao(db, now)
        val notifications = NotificationDao(db, now)
        val cipher = CardTokenCipher(settings.cardKeys, settings.currentCardKeyId, settings.tokenHmacKey)
        val clients = MonobankClients(settings)
        metrics = PluginMetrics()
        payments = PaymentService(transactions, paymentMethods, clients, tenants, cipher, metrics, now)
        val cards = CardService(paymentMethods, clients, tenants, cipher)
        val settler =
            object : TransactionSettler {
                override fun settle(row: TxRow) {
                    settleHook(row)
                    settled += row
                    transactions.markSettled(row.recordId)
                }

                override fun reviewedStatus(row: TxRow): TxState? = reviewed[row.recordId]
            }
        newHandler = {
            NotificationHandler(
                settings,
                tenants,
                MonobankClients(settings), // its own clients: a fresh webhook key cache
                transactions,
                paymentMethods,
                notifications,
                payments,
                settler,
                metrics,
                now,
            )
        }
        handler = newHandler()
        newReconcileJob = {
            ReconcileJob(
                settings,
                tenants,
                clients,
                transactions,
                paymentMethods,
                notifications,
                payments,
                cards,
                settler,
                metrics,
                now,
            )
        }
    }

    protected fun systemSettings(): SystemSettings {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        val p = SystemSettings.PREFIX
        return SystemSettings.load(
            mapOf(
                "${p}kbUsername" to "u",
                "${p}kbPassword" to "p",
                "${p}cardKeys" to "k1:$key",
                "${p}tokenHmacKey" to key,
                "${p}apiBaseUrl" to fake.localBaseUrl,
                "${p}readTimeoutSeconds" to "2",
                "${p}statementMinIntervalSeconds" to "1",
            )::get,
        ) { null }
    }

    protected fun tenants(): TenantSettingsProvider {
        val config =
            """
            org.killbill.billing.plugin.monobank.merchantToken=env:it
            org.killbill.billing.plugin.monobank.webhookUrl=http://127.0.0.1:${receiver.address.port}/hook
            org.killbill.billing.plugin.monobank.allowedCurrencies=UAH,EUR,USD
            org.killbill.billing.plugin.monobank.splitReceiverId=${SplitReceiver.DEFAULT.id}
            """.trimIndent()
        return TenantSettingsProvider(
            readRaw = { config },
            boundAlias = { "it" },
            env = mapOf("MONOBANK_MERCHANT_TOKEN_IT" to fake.token)::get,
        )
    }

    @AfterAll
    fun stop() {
        runCatching { receiver.stop(0) }
        runCatching { fake.close() }
        runCatching { pg.stop() }
    }

    @BeforeEach
    fun freshClock() {
        clock = Instant.now()
        fake.setWebhookDelivery(WebhookDelivery.AUTOMATIC)
        // A job per test: its statement rate limit must not carry over from another test.
        reconcile = newReconcileJob()
        settleHook = {}
    }

    @AfterEach
    fun clearFaults() {
        fake.clearFaults()
        fake.resume()
        fake.setWebhookDelivery(WebhookDelivery.AUTOMATIC)
        fake.dropPendingWebhooks { true }
        reconcile.close()
    }

    protected fun op(
        type: TransactionType,
        account: UUID,
        payment: UUID,
        pm: UUID?,
        amount: String?,
        vararg props: Pair<String, String>,
    ) = Operation(
        type = type,
        tenantId = tenantId,
        accountId = account,
        paymentId = payment,
        transactionId = UUID.randomUUID(),
        paymentMethodId = pm,
        amount = amount?.let(::BigDecimal),
        currency = Currency.UAH,
        properties = Properties(props.map { PluginProperty(it.first, it.second, false) }),
    )

    protected fun stored(answer: Answer): TxRow = (answer as Answer.Stored).row

    protected fun awaitRow(
        row: TxRow,
        done: (TxRow) -> Boolean,
    ): TxRow {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        var current = transactions.findByRecord(row.recordId)!!
        while (!done(current)) {
            check(System.nanoTime() < deadline) { "row never reached the expected state: $current" }
            Thread.sleep(100)
            current = transactions.findByRecord(row.recordId)!!
        }
        return current
    }

    /** An account with a card saved through a paid hosted page. */
    protected fun cardHolder(): Pair<UUID, UUID> {
        val account = UUID.randomUUID()
        val pm = UUID.randomUUID()
        paymentMethods.add(tenantId, account, pm)
        val hosted =
            stored(
                payments.charge(
                    op(
                        TransactionType.PURCHASE,
                        account,
                        UUID.randomUUID(),
                        pm,
                        "1.00",
                        "hosted" to "true",
                        "saveCard" to "true",
                    ),
                ),
            )
        fake.payInvoice(hosted.invoiceId!!)
        awaitRow(hosted) { it.state == TxState.SUCCESS }
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (paymentMethods.find(pm)!!.cardStatus != CardStatus.ACTIVE) {
            check(System.nanoTime() < deadline) { "card never saved" }
            Thread.sleep(100)
        }
        return account to pm
    }

    /** A saved card's UAH hold; returns the account, the payment method and the payment. */
    protected fun hold(amount: String = "10.00"): Triple<UUID, UUID, UUID> {
        val (account, pm) = cardHolder()
        val payment = UUID.randomUUID()
        val auth = stored(payments.charge(op(TransactionType.AUTHORIZE, account, payment, pm, amount)))
        assertThat(auth.state).isEqualTo(TxState.SUCCESS)
        assertThat(fake.invoice(auth.invoiceId!!)!!.status).isEqualTo("hold")
        return Triple(account, pm, payment)
    }

    protected fun refusalCode(answer: Answer): String = (answer as Answer.Refused).code

    protected fun hostedPage(amount: String = "3.00"): TxRow =
        stored(
            payments.charge(
                op(TransactionType.PURCHASE, UUID.randomUUID(), UUID.randomUUID(), null, amount, "hosted" to "true"),
            ),
        )
}
