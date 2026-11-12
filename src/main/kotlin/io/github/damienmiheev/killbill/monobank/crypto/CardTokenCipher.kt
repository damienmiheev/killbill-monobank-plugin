/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.crypto

import io.github.damienmiheev.killbill.monobank.config.Secret
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts monobank card tokens at rest (design A8): AES-256-GCM with a random 96-bit nonce, the ciphertext
 * bound to its row by additional data `tenantId|paymentMethodId`, and a key id stored next to it so keys can be
 * rotated. [hmac] lets the plugin recognise the same card saved twice without decrypting anything.
 */
class CardTokenCipher(
    keys: Map<String, Secret>,
    private val currentKeyId: String,
    hmacKey: Secret,
    private val random: SecureRandom = SecureRandom(),
) {
    private val keys: Map<String, SecretKeySpec> =
        keys.mapValues { (_, key) -> SecretKeySpec(Base64.getDecoder().decode(key.reveal()), "AES") }
    private val hmacKey = SecretKeySpec(Base64.getDecoder().decode(hmacKey.reveal()), HMAC)

    init {
        require(currentKeyId in this.keys) { "current card key id is not among the card keys" }
    }

    class Sealed(
        val keyId: String,
        val ciphertext: ByteArray,
    )

    fun seal(
        token: String,
        tenantId: UUID,
        paymentMethodId: UUID,
    ): Sealed {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.getValue(currentKeyId), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(tenantId, paymentMethodId))
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        return Sealed(
            currentKeyId,
            ByteBuffer
                .allocate(nonce.size + encrypted.size)
                .put(nonce)
                .put(encrypted)
                .array(),
        )
    }

    /** Decrypts a token; throws [CardTokenUnreadableException] if the key is gone or the data was tampered with. */
    fun open(
        sealed: Sealed,
        tenantId: UUID,
        paymentMethodId: UUID,
    ): String {
        val key = keys[sealed.keyId] ?: throw CardTokenUnreadableException("card key ${sealed.keyId} is not configured")
        if (sealed.ciphertext.size <=
            NONCE_BYTES
        ) {
            throw CardTokenUnreadableException("card token ciphertext is too short")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.ciphertext, 0, NONCE_BYTES))
        cipher.updateAAD(aad(tenantId, paymentMethodId))
        return try {
            String(
                cipher.doFinal(sealed.ciphertext, NONCE_BYTES, sealed.ciphertext.size - NONCE_BYTES),
                StandardCharsets.UTF_8,
            )
        } catch (e: AEADBadTagException) {
            throw CardTokenUnreadableException("card token does not belong to this payment method or was altered", e)
        }
    }

    fun hmac(token: String): ByteArray =
        Mac.getInstance(HMAC).run {
            init(hmacKey)
            doFinal(token.toByteArray(StandardCharsets.UTF_8))
        }

    private fun aad(
        tenantId: UUID,
        paymentMethodId: UUID,
    ): ByteArray = "$tenantId|$paymentMethodId".toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val HMAC = "HmacSHA256"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}

class CardTokenUnreadableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
