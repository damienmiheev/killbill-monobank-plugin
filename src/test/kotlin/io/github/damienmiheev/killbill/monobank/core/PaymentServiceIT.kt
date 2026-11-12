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
 * The payment logic against the SDK's fake monobank on this machine and a real PostgreSQL, without Kill Bill, with a
 * clock the test controls. Covers what the Kill Bill tests cannot reach: requests that never left, unknown outcomes
 * resolved from the statement, the 24-hour close, lost card webhooks, page expiry, concurrent charges, duplicate and
 * reordered webhooks, amount mismatches.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentServiceIT {
    private lateinit var pg: PostgreSQLContainer
    private lateinit var fake: FakeMonobankAcquiring
    private lateinit var receiver: HttpServer
    private var clock = Instant.now()
    private val tenantId = UUID.randomUUID()
    private val settled = CopyOnWriteArrayList<TxRow>()

    private lateinit var transactions: TransactionDao
    private lateinit var paymentMethods: PaymentMethodDao
    private lateinit var payments: PaymentService
    private lateinit var handler: NotificationHandler
    private lateinit var reconcile: ReconcileJob
    private lateinit var metrics: PluginMetrics

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
        ds.connection.use { c ->
            c.createStatement().use { it.execute(Files.readString(Path.of(System.getProperty("plugin.ddl")))) }
        }
        fake = FakeMonobankAcquiring().start()
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
            TransactionSettler { row ->
                settled += row
                transactions.markSettled(row.recordId)
            }
        handler =
            NotificationHandler(
                settings,
                tenants,
                clients,
                transactions,
                paymentMethods,
                notifications,
                payments,
                settler,
                metrics,
                now,
            )
        reconcile =
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

    private fun systemSettings(): SystemSettings {
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

    private fun tenants(): TenantSettingsProvider {
        val config =
            """
            org.killbill.billing.plugin.monobank.merchantToken=env:it
            org.killbill.billing.plugin.monobank.webhookUrl=http://127.0.0.1:${receiver.address.port}/hook
            org.killbill.billing.plugin.monobank.allowedCurrencies=UAH,USD
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
    }

    @AfterEach
    fun clearFaults() {
        fake.clearFaults()
        runCatching { fake.resume() }
    }

    private fun op(
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

    private fun stored(answer: Answer): TxRow = (answer as Answer.Stored).row

    private fun awaitRow(
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
    private fun cardHolder(): Pair<UUID, UUID> {
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

    @Test
    fun `a request that never left is CANCELED and a retry with a new transaction succeeds`() {
        val (account, pm) = cardHolder()
        fake.pause()
        val refused = stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "5.00")))
        runCatching { fake.resume() }

        assertThat(refused.state).isEqualTo(TxState.CANCELED)
        assertThat(refused.errorCode).isEqualTo(PaymentService.NOT_SENT)
        assertThat(stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "5.00"))).state)
            .isEqualTo(TxState.SUCCESS)
    }

    @Test
    fun `the same Kill Bill transaction never reaches monobank twice`() {
        val (account, pm) = cardHolder()
        val first = op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "7.00")
        val before = fake.requestCount(MonobankPaths.WALLET_PAYMENT)

        val a = stored(payments.charge(first))
        val b = stored(payments.charge(first))

        assertThat(b.recordId).isEqualTo(a.recordId)
        assertThat(fake.requestCount(MonobankPaths.WALLET_PAYMENT) - before).isEqualTo(1)
    }

    @Test
    fun `an unknown charge with no invoice id is found in the statement`() {
        val (account, pm) = cardHolder()
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        fake.injectFault(MonobankPaths.WALLET_PAYMENT, Fault.timeout())
        val unknown = stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "9.00")))
        fake.clearFaults()
        assertThat(unknown.state).isEqualTo(TxState.UNKNOWN)
        assertThat(unknown.invoiceId).isNull()

        clock = clock.plus(Duration.ofMinutes(3))
        reconcile.runOnce()

        val found = transactions.findByRecord(unknown.recordId)!!
        assertThat(found.state).isEqualTo(TxState.SUCCESS)
        assertThat(found.invoiceId).isNotNull
        assertThat(settled.map { it.recordId }).contains(unknown.recordId)
    }

    @Test
    fun `an unknown charge that left no trace stays UNKNOWN, blocks the account, and closes after 24 hours`() {
        val (account, pm) = cardHolder()
        fake.injectFault(MonobankPaths.WALLET_PAYMENT, Fault.timeoutWithoutProcessing())
        val unknown = stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "11.00")))
        fake.clearFaults()
        assertThat(unknown.state).isEqualTo(TxState.UNKNOWN)

        val blocked = payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "11.00"))
        assertThat((blocked as Answer.Refused).code).isEqualTo(PaymentService.PREVIOUS_CHARGE_UNRESOLVED)

        clock = clock.plus(Duration.ofHours(1))
        reconcile.runOnce()
        assertThat(transactions.findByRecord(unknown.recordId)!!.state).isEqualTo(TxState.UNKNOWN)

        clock = clock.plus(Duration.ofHours(24))
        Thread.sleep(1100) // the statement is read at most once a second per tenant in this test
        reconcile.runOnce()
        assertThat(transactions.findByRecord(unknown.recordId)!!.state).isEqualTo(TxState.FAILED)
        assertThat(metrics.unknownClosed.get()).isGreaterThan(0)
    }

    @Test
    fun `two concurrent charges on one account reach monobank once`() {
        val (account, pm) = cardHolder()
        fake.injectFault(MonobankPaths.WALLET_PAYMENT, Fault.delay(Duration.ofMillis(800)))
        val before = fake.requestCount(MonobankPaths.WALLET_PAYMENT)
        val pool = Executors.newFixedThreadPool(2)
        val answers =
            pool
                .invokeAll(
                    List(2) {
                        Callable {
                            payments.charge(
                                op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "3.00"),
                            )
                        }
                    },
                ).map { it.get() }
        pool.shutdown()

        assertThat(
            answers.count { it is Answer.Refused && it.code == PaymentService.PREVIOUS_CHARGE_UNRESOLVED },
        ).isEqualTo(1)
        assertThat(fake.requestCount(MonobankPaths.WALLET_PAYMENT) - before).isEqualTo(1)
    }

    @Test
    fun `a lost card webhook is recovered by polling the invoice that saved it`() {
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
        fake.dropNextWebhooks(10) { it.invoiceId == hosted.invoiceId }
        fake.payInvoice(hosted.invoiceId!!)
        Thread.sleep(500)
        assertThat(paymentMethods.find(pm)!!.cardStatus).isEqualTo(CardStatus.PENDING)

        clock = clock.plus(Duration.ofMinutes(3))
        reconcile.runOnce()

        assertThat(paymentMethods.find(pm)!!.cardStatus).isEqualTo(CardStatus.ACTIVE)
        assertThat(transactions.findByRecord(hosted.recordId)!!.state).isEqualTo(TxState.SUCCESS)
    }

    @Test
    fun `an expired page fails only on monobank's word, and a page paid at the last moment stays paid`() {
        val account = UUID.randomUUID()
        val unpaid =
            stored(
                payments.charge(
                    op(
                        TransactionType.PURCHASE,
                        account,
                        UUID.randomUUID(),
                        null,
                        "4.00",
                        "hosted" to "true",
                    ),
                ),
            )
        val paid =
            stored(
                payments.charge(
                    op(
                        TransactionType.PURCHASE,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "4.00",
                        "hosted" to "true",
                    ),
                ),
            )
        fake.dropNextWebhooks(10) { it.invoiceId == paid.invoiceId }
        fake.payInvoice(paid.invoiceId!!)

        clock = clock.plus(Duration.ofHours(2))
        reconcile.runOnce()

        assertThat(transactions.findByRecord(unpaid.recordId)!!.state).isEqualTo(TxState.FAILED)
        assertThat(fake.invoice(unpaid.invoiceId!!)!!.status).isEqualTo("expired")
        assertThat(transactions.findByRecord(paid.recordId)!!.state).isEqualTo(TxState.SUCCESS)
    }

    @Test
    fun `duplicate and reordered webhooks end in the newest state`() {
        val account = UUID.randomUUID()
        val hosted =
            stored(
                payments.charge(
                    op(
                        TransactionType.PURCHASE,
                        account,
                        UUID.randomUUID(),
                        null,
                        "6.00",
                        "hosted" to "true",
                    ),
                ),
            )
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        fake.payInvoice(hosted.invoiceId!!)

        val delivered = fake.deliverWebhooksInReverseOrder()
        delivered.forEach { fake.redeliver(it) }

        assertThat(transactions.findByRecord(hosted.recordId)!!.state).isEqualTo(TxState.SUCCESS)
        assertThat(settled.count { it.recordId == hosted.recordId }).isEqualTo(1)
    }

    @Test
    fun `an amount that does not match goes to REVIEW`() {
        val hosted =
            stored(
                payments.charge(
                    op(
                        TransactionType.PURCHASE,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "8.00",
                        "hosted" to "true",
                    ),
                ),
            )
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        fake.payInvoice(hosted.invoiceId!!)
        fake.dropPendingWebhooks { true }
        val body = fake.invoice(hosted.invoiceId)!!.statusJson.replace("\"amount\":800", "\"amount\":1")
        val result = handler.handle(body.toByteArray(), fake.signer.sign(body), null)

        assertThat(result).isEqualTo(NotificationHandler.Result.APPLIED)
        assertThat(transactions.findByRecord(hosted.recordId)!!.state).isEqualTo(TxState.REVIEW)
        assertThat(metrics.amountMismatch.get()).isGreaterThan(0)
    }

    @Test
    fun `a forged signature is ignored and a missing key asks monobank to retry`() {
        val hosted =
            stored(
                payments.charge(
                    op(
                        TransactionType.PURCHASE,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "2.00",
                        "hosted" to "true",
                    ),
                ),
            )
        val body = fake.invoice(hosted.invoiceId!!)!!.statusJson.replace("\"created\"", "\"success\"")

        assertThat(
            handler.handle(body.toByteArray(), "bm90IGEgc2lnbmF0dXJl", null),
        ).isEqualTo(NotificationHandler.Result.IGNORED)
        assertThat(transactions.findByRecord(hosted.recordId)!!.state).isEqualTo(TxState.PENDING)
    }

    @Test
    fun `a refund whose answer was lost is settled from the invoice's cancel list`() {
        val (account, pm) = cardHolder()
        val payment = UUID.randomUUID()
        assertThat(
            stored(payments.charge(op(TransactionType.PURCHASE, account, payment, pm, "20.00"))).state,
        ).isEqualTo(TxState.SUCCESS)

        fake.setWebhookDelivery(WebhookDelivery.MANUAL) // the answer and the webhook are both lost
        fake.injectFault(MonobankPaths.INVOICE_CANCEL, Fault.timeout())
        val refund = stored(payments.refund(op(TransactionType.REFUND, account, payment, pm, "5.00")))
        fake.clearFaults()
        assertThat(refund.state).isEqualTo(TxState.UNKNOWN)

        val tooMuch = payments.refund(op(TransactionType.REFUND, account, payment, pm, "20.00"))
        assertThat(tooMuch).isInstanceOf(Answer.Refused::class.java)

        fake.dropPendingWebhooks { true }
        clock = clock.plus(Duration.ofMinutes(3))
        reconcile.runOnce()
        assertThat(awaitRow(refund) { it.state.isFinal }.state).isEqualTo(TxState.SUCCESS)
    }

    @Test
    fun `a card monobank no longer knows is retired and declines without another call`() {
        val (account, pm) = cardHolder()
        val token = fake.invoices().first { it.cardToken != null && it.walletId == account.toString() }.cardToken!!
        io.github.damienmiheev.monobank.acquiring
            .MonobankAcquiring(
                io.github.damienmiheev.monobank.acquiring.AcquiringConfig
                    .builder(fake.token)
                    .options(fake.clientOptions())
                    .build(),
            ).wallet
            .deleteCard(token)

        val declined = stored(payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "1.00")))

        assertThat(declined.state).isEqualTo(TxState.FAILED)
        assertThat(declined.errorCode).isEqualTo("TOKEN_NOT_FOUND")
        assertThat(paymentMethods.find(pm)!!.cardStatus).isEqualTo(CardStatus.FAILED)
        val next = payments.charge(op(TransactionType.PURCHASE, account, UUID.randomUUID(), pm, "1.00"))
        assertThat((next as Answer.Refused).code).isEqualTo(PaymentService.NO_ACTIVE_CARD)
    }

    @Test
    fun `a basket that does not add up is refused before anything is stored`() {
        val answer =
            payments.charge(
                op(
                    TransactionType.PURCHASE,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    "5.00",
                    "hosted" to "true",
                    "basketOrder" to """[{"name":"Audit","qty":1,"sum":100,"code":"audit"}]""",
                ),
            )
        assertThat((answer as Answer.Refused).code).isEqualTo(PaymentService.INVALID_BASKET)
    }
}
