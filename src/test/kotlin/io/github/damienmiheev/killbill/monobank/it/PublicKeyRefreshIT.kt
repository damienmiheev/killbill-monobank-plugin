/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.it

import io.github.damienmiheev.monobank.testkit.FakeMonobankAcquiring
import io.github.damienmiheev.monobank.testkit.MonobankPaths
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * `GET /pubkey?refresh=true` for a forwarder that checks monobank's signature before forwarding (design A7): after
 * monobank rotates its key, the forwarder drops the webhooks that would make the plugin notice, so it asks the plugin
 * to re-read the key. Its own fake and Kill Bill: rotating the shared fake's key would break other test classes.
 * The tests run in order: a failed read (the last test) starts the SDK's back-off, which would change the others.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PublicKeyRefreshIT {
    private lateinit var fake: FakeMonobankAcquiring
    private lateinit var stack: KillBillStack

    @BeforeAll
    fun start() {
        fake = FakeMonobankAcquiring.builder().build().start()
        stack = KillBillStack(fake = fake)
        stack.start()
    }

    @AfterAll
    fun stop() {
        if (::stack.isInitialized) stack.close()
        if (::fake.isInitialized) fake.close()
    }

    @Test
    @Order(1)
    fun `pubkey needs credentials and tenant headers`() {
        val tenant = stack.moneyTenant()
        assertThat(stack.get("$PUBKEY?refresh=true", tenant = null, auth = false).status).isEqualTo(401)
        // Signed in, but no tenant headers: still 401.
        assertThat(stack.get(PUBKEY, tenant = null, auth = true).status).isEqualTo(401)
        assertThat(stack.get("$PUBKEY?refresh=true", tenant = null, auth = true).status).isEqualTo(401)
        assertThat(stack.get(PUBKEY, tenant).status).isEqualTo(200)
        // A typo is not silently a plain read.
        for (value in listOf("1", "TRUE", "")) {
            assertThat(stack.get("$PUBKEY?refresh=$value", tenant).status).describedAs(value).isEqualTo(400)
        }
    }

    @Test
    @Order(2)
    fun `refresh re-reads a rotated key, at most once a minute`() {
        val tenant = stack.moneyTenant()
        val oldKey = fake.signer.publicKey
        assertThat(key(stack.get(PUBKEY, tenant))).isEqualTo(oldKey)
        val loadedAt = System.nanoTime()

        val newKey = fake.rotateKey()
        assertThat(newKey).isNotEqualTo(oldKey)
        // Without refresh the plugin keeps the key it has: this is what left forwarders on the old key.
        assertThat(key(stack.get(PUBKEY, tenant))).isEqualTo(oldKey)
        // Within the minute after the last read monobank is not asked again.
        val reads = fake.requestCount(MonobankPaths.PUBKEY)
        assertThat(key(stack.get("$PUBKEY?refresh=true", tenant))).isEqualTo(oldKey)
        assertThat(fake.requestCount(MonobankPaths.PUBKEY)).isEqualTo(reads)

        val waited = (System.nanoTime() - loadedAt) / NANOS_PER_MILLI
        Thread.sleep(maxOf(0, REFRESH_INTERVAL_MILLIS - waited))
        val refreshed = stack.get("$PUBKEY?refresh=true", tenant)
        assertThat(refreshed.status).describedAs(refreshed.body).isEqualTo(200)
        assertThat(key(refreshed)).isEqualTo(newKey)
        assertThat(fake.requestCount(MonobankPaths.PUBKEY)).isEqualTo(reads + 1)
        assertThat(key(stack.get(PUBKEY, tenant))).isEqualTo(newKey)
        assertThat(key(stack.get("$PUBKEY?refresh=true", tenant))).isEqualTo(newKey)
        assertThat(fake.requestCount(MonobankPaths.PUBKEY)).isEqualTo(reads + 1)
    }

    @Test
    @Order(3)
    fun `refresh answers 503 when monobank's key can't be re-read`() {
        val tenant = stack.moneyTenant()
        assertThat(stack.get(PUBKEY, tenant).status).isEqualTo(200)
        Thread.sleep(REFRESH_INTERVAL_MILLIS)
        fake.pause()
        try {
            val answer = stack.get("$PUBKEY?refresh=true", tenant)
            assertThat(answer.status).describedAs(answer.body).isEqualTo(503)
        } finally {
            fake.resume()
        }
    }

    private fun key(response: KillBillStack.Response): String {
        assertThat(response.status).describedAs(response.body).isEqualTo(200)
        return response.json().path("key").asText()
    }

    private companion object {
        const val PUBKEY = "/plugins/killbill-monobank/pubkey"

        /** The SDK's minimum time between key reads (`webhookKeyRefreshInterval`, 1 minute), plus a margin. */
        const val REFRESH_INTERVAL_MILLIS = 61_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
