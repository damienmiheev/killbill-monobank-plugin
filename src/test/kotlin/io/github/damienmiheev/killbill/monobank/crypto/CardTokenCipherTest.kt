/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.crypto

import io.github.damienmiheev.killbill.monobank.config.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

class CardTokenCipherTest {
    private fun key(b: Int) = Secret(Base64.getEncoder().encodeToString(ByteArray(32) { b.toByte() }))

    private val tenant = UUID.randomUUID()
    private val pm = UUID.randomUUID()

    @Test
    fun `round trip, a fresh nonce every time, and no plaintext in the ciphertext`() {
        val cipher = CardTokenCipher(mapOf("k1" to key(1)), "k1", key(9))
        val a = cipher.seal("tok_123456", tenant, pm)
        val b = cipher.seal("tok_123456", tenant, pm)

        assertThat(cipher.open(a, tenant, pm)).isEqualTo("tok_123456")
        assertThat(a.ciphertext).isNotEqualTo(b.ciphertext)
        assertThat(String(a.ciphertext, Charsets.ISO_8859_1)).doesNotContain("tok_123456")
    }

    @Test
    fun `a token copied to another payment method or tenant does not decrypt`() {
        val cipher = CardTokenCipher(mapOf("k1" to key(1)), "k1", key(9))
        val sealed = cipher.seal("tok", tenant, pm)

        assertThatThrownBy {
            cipher.open(sealed, tenant, UUID.randomUUID())
        }.isInstanceOf(CardTokenUnreadableException::class.java)
        assertThatThrownBy {
            cipher.open(
                sealed,
                UUID.randomUUID(),
                pm,
            )
        }.isInstanceOf(CardTokenUnreadableException::class.java)
    }

    @Test
    fun `old keys stay readable after rotation, new writes use the current key, a removed key is reported`() {
        val old = CardTokenCipher(mapOf("k1" to key(1)), "k1", key(9))
        val sealed = old.seal("tok", tenant, pm)
        val rotated = CardTokenCipher(linkedMapOf("k2" to key(2), "k1" to key(1)), "k2", key(9))

        assertThat(rotated.open(sealed, tenant, pm)).isEqualTo("tok")
        assertThat(rotated.seal("tok", tenant, pm).keyId).isEqualTo("k2")
        val withoutK1 = CardTokenCipher(mapOf("k2" to key(2)), "k2", key(9))
        assertThatThrownBy { withoutK1.open(sealed, tenant, pm) }.hasMessageContaining("k1")
    }

    @Test
    fun `the hmac recognises the same card and depends on the key`() {
        val a = CardTokenCipher(mapOf("k1" to key(1)), "k1", key(9))
        val b = CardTokenCipher(mapOf("k1" to key(1)), "k1", key(8))
        assertThat(a.hmac("tok")).isEqualTo(a.hmac("tok")).isNotEqualTo(a.hmac("tok2")).isNotEqualTo(b.hmac("tok"))
    }
}
