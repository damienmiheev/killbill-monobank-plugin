/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.it

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.damienmiheev.monobank.testkit.FakeMonobankAcquiring
import org.slf4j.LoggerFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * A real Kill Bill (the image named by the `killbill.image` system property) on PostgreSQL, with the
 * plugin bundle built by this project installed the way an operator installs it.
 */
class KillBillStack(
    /** Whether to apply the plugin's DDL; without it the plugin must refuse to start. */
    private val applyPluginSchema: Boolean = true,
    /** Extra `KB_…` environment for the Kill Bill container. */
    private val extraEnv: Map<String, String> = emptyMap(),
    /** The fake monobank the plugin talks to (merchant token alias `it`), if the test moves money. */
    val fake: FakeMonobankAcquiring? = null,
    /** Fail [start] unless the plugin runs (tests of refusals set it to false). */
    private val expectRunning: Boolean = true,
) : AutoCloseable {
    private val network = Network.newNetwork()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    val db: GenericContainer<*> =
        GenericContainer(DockerImageName.parse(property("killbill.db.image")))
            .withNetwork(network)
            .withNetworkAliases("db")
            .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
            .withStartupTimeout(Duration.ofMinutes(2))

    /**
     * Kill Bill with the bundle baked into the image, as an operator ships it. (A file copied into a
     * container before its first start is lost: Kill Bill recreates its plugins directory on first boot.)
     */
    val killbill: GenericContainer<*> by lazy {
        val version = property("plugin.version")
        val bundle = Path.of(property("plugin.bundle"))
        check(Files.isRegularFile(bundle)) { "plugin bundle $bundle not built; run mvn verify" }
        val target = "/var/lib/killbill/bundles/plugins/java/killbill-monobank/$version/killbill-monobank-$version.jar"
        val image =
            ImageFromDockerfile("killbill-monobank-it", true)
                .withFileFromPath("plugin.jar", bundle)
                .withFileFromString(
                    "Dockerfile",
                    "FROM ${property("killbill.image")}\nCOPY --chown=tomcat:tomcat plugin.jar $target\n",
                )
        GenericContainer(image)
            .withNetwork(network)
            .withEnv(killbillEnv())
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/1.0/healthcheck").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(4))
            .withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("killbill")).withSeparateOutputStreams())
    }

    private var killbillCreated = false

    /** Starts everything; on failure stops whatever was started and rethrows. */
    fun start(): KillBillStack {
        try {
            db.start()
            if (applyPluginSchema) applyPluginDdl()
            fake?.let { Testcontainers.exposeHostPorts(it.port) }
            killbillCreated = true
            killbill.start()
            awaitPluginSettled()
            check(pluginState == "RUNNING" || !expectRunning) {
                "the plugin did not start:\n" +
                    killbillLogs()
                        .lines()
                        .filter {
                            "monobank" in it || "Exception" in it || "Caused" in it
                        }.takeLast(60)
                        .joinToString("\n")
            }
            if (pluginState == "RUNNING") createPluginUser()
        } catch (e: Throwable) {
            close()
            throw e
        }
        return this
    }

    /** The plugin's state once Kill Bill has finished starting it. */
    lateinit var pluginState: String
        private set

    /**
     * Kill Bill answers its health check before it has started the plugins, so wait until node info reports the
     * plugin as RUNNING or as STOPPED (it refused to start), not in between.
     */
    private fun awaitPluginSettled() {
        val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
        while (System.nanoTime() < deadline) {
            val state = runCatching { pluginInfo()?.path("state")?.asText() }.getOrNull()
            if (state == "RUNNING" || state == "STOPPED") {
                pluginState = state
                return
            }
            Thread.sleep(1000)
        }
        val bundles = killbill.execInContainer("sh", "-c", "ls -lR /var/lib/killbill/bundles/plugins").stdout
        val nodes = runCatching { get("/1.0/kb/nodesInfo").body }.getOrElse { it.toString() }
        error(
            "Kill Bill never listed killbill-monobank.\nnodesInfo: $nodes\nbundles:\n$bundles\nlogs:\n" +
                killbillLogs()
                    .lines()
                    .filter { "monobank" in it || "ERROR" in it }
                    .takeLast(80)
                    .joinToString("\n"),
        )
    }

    private fun killbillEnv(): Map<String, String> {
        val jdbc = "jdbc:postgresql://db:5432/killbill"
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        return mapOf(
            "KB_org_killbill_dao_url" to jdbc,
            "KB_org_killbill_dao_user" to "postgres",
            "KB_org_killbill_dao_password" to DB_PASSWORD,
            "KB_org_killbill_billing_osgi_dao_url" to jdbc,
            "KB_org_killbill_billing_osgi_dao_user" to "postgres",
            "KB_org_killbill_billing_osgi_dao_password" to DB_PASSWORD,
            "KB_org_killbill_server_test_mode" to "true",
            "KB_org_killbill_billing_plugin_monobank_kbUsername" to "monobank-plugin",
            "KB_org_killbill_billing_plugin_monobank_kbPassword" to "env:IT_PLUGIN_PASSWORD",
            "IT_PLUGIN_PASSWORD" to "plugin-password",
            "KB_org_killbill_billing_plugin_monobank_cardKeys" to "it1:$key",
            "KB_org_killbill_billing_plugin_monobank_tokenHmacKey" to key,
            "KB_org_killbill_billing_plugin_monobank_merchantTokenBindings" to "$BOUND_API_KEY:it",
            "MONOBANK_MERCHANT_TOKEN_IT" to (fake?.token ?: "test-merchant-token"),
            "KB_org_killbill_billing_plugin_monobank_apiBaseUrl" to
                (fake?.let { "http://host.testcontainers.internal:${it.port}" } ?: "https://api.monobank.ua"),
            "KB_org_killbill_billing_plugin_monobank_readTimeoutSeconds" to "3",
            "KB_org_killbill_billing_plugin_monobank_reconcileIntervalSeconds" to "30",
            "KB_org_killbill_billing_plugin_monobank_statementMinIntervalSeconds" to "1",
            "MONOBANK_MERCHANT_TOKEN_OTHER" to "another-tenants-token",
        ) + extraEnv
    }

    private fun applyPluginDdl() {
        val ddl = Files.readString(Path.of(property("plugin.ddl")))
        val url = "jdbc:postgresql://${db.host}:${db.getMappedPort(5432)}/killbill"
        DriverManager.getConnection(url, "postgres", DB_PASSWORD).use { conn ->
            conn.createStatement().use { it.execute(ddl) }
        }
    }

    // ---- Kill Bill REST helpers ---------------------------------------------------------------------------------

    val baseUrl: String get() = "http://${killbill.host}:${killbill.getMappedPort(8080)}"

    fun get(
        path: String,
        tenant: TestTenant? = null,
        auth: Boolean = true,
    ): Response = send(request(path, tenant, auth).GET())

    /** POSTs as admin, as [user] (name to password), or with no credentials when [auth] is false. */
    fun post(
        path: String,
        body: String,
        contentType: String,
        tenant: TestTenant? = null,
        auth: Boolean = true,
        user: Pair<String, String>? = null,
    ): Response =
        send(
            request(path, tenant, auth, user)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )

    fun put(
        path: String,
        tenant: TestTenant,
    ): Response =
        send(
            request(path, tenant, auth = true)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.noBody()),
        )

    fun delete(
        path: String,
        tenant: TestTenant,
    ): Response = send(request(path, tenant, auth = true).DELETE())

    /** A Kill Bill user with exactly [permissions]; returns its name and password. */
    fun createUser(permissions: List<String>): Pair<String, String> {
        val name = "it-user-${UUID.randomUUID()}"
        val role = JSON.writeValueAsString(mapOf("role" to "$name-role", "permissions" to permissions))
        check(post("/1.0/kb/security/roles", role, "application/json").status == 201) { "role creation failed" }
        val user =
            JSON.writeValueAsString(
                mapOf("username" to name, "password" to "pw-$name", "roles" to listOf("$name-role")),
            )
        check(post("/1.0/kb/security/users", user, "application/json").status == 201) { "user creation failed" }
        return name to "pw-$name"
    }

    /** The plugin's own Kill Bill user, with only `payment:transition` (design A7). */
    private fun createPluginUser() {
        val role =
            JSON.writeValueAsString(
                mapOf(
                    "role" to "monobank-plugin",
                    "permissions" to listOf("payment:transition"),
                ),
            )
        check(
            post("/1.0/kb/security/roles", role, "application/json").status in setOf(201, 409),
        ) { "role creation failed" }
        val user =
            JSON.writeValueAsString(
                mapOf(
                    "username" to "monobank-plugin",
                    "password" to "plugin-password",
                    "roles" to listOf("monobank-plugin"),
                ),
            )
        val response = post("/1.0/kb/security/users", user, "application/json")
        check(response.status in setOf(201, 409)) { "plugin user creation failed: ${response.status} ${response.body}" }
    }

    /** The URL monobank (the fake, on this machine) posts webhooks to. */
    val webhookUrl: String get() = "$baseUrl/plugins/killbill-monobank/notification"

    private var boundTenant: TestTenant? = null

    /**
     * The tenant bound to the fake's merchant (API key [BOUND_API_KEY]), with valid plugin settings pointing webhooks
     * at this Kill Bill. Created once per stack; tests isolate themselves by account.
     */
    @Synchronized
    fun moneyTenant(extraConfig: String = ""): TestTenant {
        boundTenant?.let { return it }
        val tenant = createTenant(BOUND_API_KEY).also { boundTenant = it }
        uploadPluginConfig(
            tenant,
            """
            org.killbill.billing.plugin.monobank.merchantToken=env:it
            org.killbill.billing.plugin.monobank.webhookUrl=$webhookUrl
            org.killbill.billing.plugin.monobank.redirectUrl=https://shop.example/return
            org.killbill.billing.plugin.monobank.allowedCurrencies=UAH,USD
            """.trimIndent() + "\n" + extraConfig,
        )
        return tenant
    }

    fun createAccount(
        tenant: TestTenant,
        currency: String = "UAH",
    ): String {
        val body =
            JSON.writeValueAsString(
                mapOf(
                    "name" to "Test customer",
                    "currency" to currency,
                    "externalKey" to UUID.randomUUID().toString(),
                ),
            )
        val response = post("/1.0/kb/accounts", body, "application/json", tenant)
        check(response.status == 201) { "account creation failed: ${response.status} ${response.body}" }
        return UUID_PATTERN.findAll(response.location()!!).last().value
    }

    fun addPaymentMethod(
        tenant: TestTenant,
        accountId: String,
        isDefault: Boolean = true,
    ): String {
        val body =
            JSON.writeValueAsString(
                mapOf(
                    "pluginName" to "killbill-monobank",
                    "pluginInfo" to mapOf<String, Any>(),
                ),
            )
        val response =
            post("/1.0/kb/accounts/$accountId/paymentMethods?isDefault=$isDefault", body, "application/json", tenant)
        check(response.status == 201) { "payment method creation failed: ${response.status} ${response.body}" }
        return UUID_PATTERN.findAll(response.location()!!).last().value
    }

    /** Creates a payment transaction and returns the payment as Kill Bill shows it (with plugin info). */
    fun pay(
        tenant: TestTenant,
        accountId: String,
        paymentMethodId: String,
        type: String,
        amount: String,
        currency: String = "UAH",
        properties: Map<String, String> = emptyMap(),
    ): JsonNode {
        val body =
            JSON.writeValueAsString(
                mapOf(
                    "transactionType" to type,
                    "amount" to amount,
                    "currency" to currency,
                    "paymentExternalKey" to UUID.randomUUID().toString(),
                ),
            )
        val query =
            "paymentMethodId=$paymentMethodId" +
                properties.entries.joinToString("") { "&pluginProperty=${it.key}%3D${encode(it.value)}" }
        val response = post("/1.0/kb/accounts/$accountId/payments?$query", body, "application/json", tenant)
        check(
            response.status in setOf(201, 402, 422, 502, 503),
        ) { "payment failed: ${response.status} ${response.body}" }
        val paymentId =
            response.location()?.let { loc -> UUID_PATTERN.findAll(loc).lastOrNull()?.value }
                ?: response
                    .json()
                    .path(
                        "paymentId",
                    ).asText()
                    .ifEmpty { error("no payment in ${response.status} ${response.body}") }
        return payment(tenant, paymentId)
    }

    /** A follow-up transaction (CAPTURE, REFUND, VOID) on an existing payment. */
    fun followUp(
        tenant: TestTenant,
        paymentId: String,
        type: String,
        amount: String? = null,
        currency: String = "UAH",
    ): Response {
        val path =
            when (type) {
                "CAPTURE" -> "/1.0/kb/payments/$paymentId"
                "REFUND" -> "/1.0/kb/payments/$paymentId/refunds"
                else -> "/1.0/kb/payments/$paymentId"
            }
        val body =
            JSON.writeValueAsString(
                mapOf("amount" to amount, "currency" to currency).filterValues { it != null },
            )
        return if (type == "VOID") {
            send(
                request(
                    path,
                    tenant,
                    auth = true,
                ).header(
                    "Content-Type",
                    "application/json",
                ).method("DELETE", HttpRequest.BodyPublishers.ofString(body)),
            )
        } else {
            post(path, body, "application/json", tenant)
        }
    }

    fun payment(
        tenant: TestTenant,
        paymentId: String,
        withPluginInfo: Boolean = false,
    ): JsonNode = get("/1.0/kb/payments/$paymentId?withPluginInfo=$withPluginInfo", tenant).json()

    /** Polls the payment until [done] holds, or fails after [timeout]. */
    fun awaitPayment(
        tenant: TestTenant,
        paymentId: String,
        timeout: Duration = Duration.ofSeconds(30),
        done: (JsonNode) -> Boolean,
    ): JsonNode {
        val deadline = System.nanoTime() + timeout.toNanos()
        var last = payment(tenant, paymentId)
        while (!done(last)) {
            check(System.nanoTime() < deadline) { "payment $paymentId never reached the expected state: $last" }
            Thread.sleep(POLL_MS)
            last = payment(tenant, paymentId)
        }
        return last
    }

    /** Creates a tenant; by default with a fresh API key, which no merchant-token alias is bound to. */
    fun createTenant(apiKey: String = "it-${UUID.randomUUID()}"): TestTenant {
        val tenant = TestTenant(apiKey, "secret-${UUID.randomUUID()}")
        val body = JSON.writeValueAsString(mapOf("apiKey" to tenant.apiKey, "apiSecret" to tenant.apiSecret))
        val response = post("/1.0/kb/tenants?useGlobalDefault=false", body, "application/json")
        check(response.status == 201) { "tenant creation failed: ${response.status} ${response.body}" }
        return tenant
    }

    fun uploadPluginConfig(
        tenant: TestTenant,
        config: String,
    ) {
        val response = post("/1.0/kb/tenants/uploadPluginConfig/killbill-monobank", config, "text/plain", tenant)
        check(response.status == 201) { "plugin config upload failed: ${response.status} ${response.body}" }
    }

    /** The plugin as Kill Bill's node info reports it, or null when Kill Bill does not list it. */
    fun pluginInfo(): JsonNode? =
        get("/1.0/kb/nodesInfo")
            .json()
            .flatMap { node -> node.path("pluginsInfo").toList() }
            .firstOrNull { it.path("pluginName").asText() == "killbill-monobank" }

    private fun request(
        path: String,
        tenant: TestTenant?,
        auth: Boolean,
        user: Pair<String, String>? = null,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("X-Killbill-CreatedBy", "it")
        if (auth) {
            val (name, password) = user ?: ("admin" to "password")
            builder.header(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString("$name:$password".toByteArray()),
            )
        }
        if (tenant !=
            null
        ) {
            builder.header("X-Killbill-ApiKey", tenant.apiKey).header("X-Killbill-ApiSecret", tenant.apiSecret)
        }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): Response {
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.body(), response.headers().map())
    }

    /** Kill Bill's output plus its log files (Kill Bill writes most of its logs to files, not stdout). */
    fun killbillLogs(): String =
        killbill.logs + killbill.execInContainer("sh", "-c", "cat /var/lib/tomcat/logs/* 2>/dev/null").stdout

    override fun close() {
        if (killbillCreated) {
            runCatching {
                val dump = Path.of("target", "killbill-${System.currentTimeMillis()}.log")
                Files.writeString(
                    dump,
                    killbillLogs()
                        .lines()
                        .filter {
                            "monobank" in it || "\"level\":\"ERROR\"" in it
                        }.joinToString("\n"),
                )
            }
        }
        if (killbillCreated) runCatching { killbill.stop() }
        runCatching { db.stop() }
        runCatching { network.close() }
    }

    data class TestTenant(
        val apiKey: String,
        val apiSecret: String,
    )

    data class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>> = emptyMap(),
    ) {
        fun json(): JsonNode = JSON.readTree(body)

        fun location(): String? =
            headers.entries
                .firstOrNull {
                    it.key.equals("location", ignoreCase = true)
                }?.value
                ?.firstOrNull()
    }

    companion object {
        /** The API key the stack binds the merchant-token alias `it` to (MONOBANK_MERCHANT_TOKEN_IT). */
        const val BOUND_API_KEY = "it-bound-tenant"
        private const val DB_PASSWORD = "killbill"
        private const val POLL_MS = 300L
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

        private val JSON = ObjectMapper()

        private fun property(name: String): String =
            System.getProperty(name)
                ?: error("system property $name is not set; run the tests through Maven (mvn verify)")
    }
}
