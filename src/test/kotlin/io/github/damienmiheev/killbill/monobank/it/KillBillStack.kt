/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.it

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
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
            killbillCreated = true
            killbill.start()
            awaitPluginSettled()
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
            "MONOBANK_MERCHANT_TOKEN_IT" to "test-merchant-token",
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

    private val baseUrl: String get() = "http://${killbill.host}:${killbill.getMappedPort(8080)}"

    fun get(
        path: String,
        tenant: TestTenant? = null,
        auth: Boolean = true,
    ): Response = send(request(path, tenant, auth).GET())

    fun post(
        path: String,
        body: String,
        contentType: String,
        tenant: TestTenant? = null,
    ): Response =
        send(
            request(
                path,
                tenant,
                auth = true,
            ).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)),
        )

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
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("X-Killbill-CreatedBy", "it")
        if (auth) {
            builder.header(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString("admin:password".toByteArray()),
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
        return Response(response.statusCode(), response.body())
    }

    /** Kill Bill's output plus its log files (Kill Bill writes most of its logs to files, not stdout). */
    fun killbillLogs(): String =
        killbill.logs + killbill.execInContainer("sh", "-c", "cat /var/lib/tomcat/logs/* 2>/dev/null").stdout

    override fun close() {
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
    ) {
        fun json(): JsonNode = JSON.readTree(body)
    }

    companion object {
        /** The API key the stack binds the merchant-token alias `it` to (MONOBANK_MERCHANT_TOKEN_IT). */
        const val BOUND_API_KEY = "it-bound-tenant"
        private const val DB_PASSWORD = "killbill"
        private val JSON = ObjectMapper()

        private fun property(name: String): String =
            System.getProperty(name)
                ?: error("system property $name is not set; run the tests through Maven (mvn verify)")
    }
}
