package com.vasmarfas.notivisor.core.protocol

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WireCodec(private val key: SecretKey?) {

    class ProtocolMismatch(message: String) : Exception(message)

    private val random = SecureRandom()

    val encrypted: Boolean get() = key != null

    fun encode(envelope: Envelope): String {
        val json = envelope.toJson()
        val k = key ?: return json
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        val sealed = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        return ENC_PREFIX + Base64.encodeToString(iv + sealed, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decode(line: String): Envelope {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) throw ProtocolMismatch("empty line")

        val json = when {
            trimmed.startsWith(ENC_PREFIX) -> {
                val k = key
                    ?: throw ProtocolMismatch("peer sends encrypted lines, we have no pairing key")
                val raw = Base64.decode(
                    trimmed.removePrefix(ENC_PREFIX),
                    Base64.NO_WRAP or Base64.NO_PADDING
                )
                if (raw.size <= IV_BYTES) throw ProtocolMismatch("truncated frame (${raw.size} B)")
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
                cipher.updateAAD(AAD)
                String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
            }

            trimmed.startsWith("{") -> {
                if (key != null) throw ProtocolMismatch("peer sends cleartext while we expect encryption")
                trimmed
            }

            else -> throw ProtocolMismatch("unrecognised frame prefix '${trimmed.take(4)}'")
        }

        val envelope = Envelope.parse(json)
        if (envelope.v != PROTOCOL_VERSION) {
            throw ProtocolMismatch("protocol v${envelope.v}, this build speaks v$PROTOCOL_VERSION")
        }
        return envelope
    }

    companion object {
        const val ENC_PREFIX = "E1."
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val AAD = "Notivisor/v$PROTOCOL_VERSION".toByteArray(Charsets.UTF_8)
    }
}

object Pairing {

    const val CODE_LENGTH = 16
    private const val ITERATIONS = 120_000
    private val SALT = "Notivisor/v1/pairing".toByteArray(Charsets.UTF_8)

    private val random = SecureRandom()

    fun generateCode(): String = buildString {
        repeat(CODE_LENGTH) { append(random.nextInt(10)) }
    }

    fun format(code: String): String = code.chunked(4).joinToString(" ")

    fun normalise(input: String): String = input.filter { it.isDigit() }

    fun isValid(code: String): Boolean = code.length == CODE_LENGTH && code.all { it.isDigit() }

    fun deriveKey(code: String): SecretKey {
        val spec = PBEKeySpec(code.toCharArray(), SALT, ITERATIONS, 256)
        val bytes =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encodeKey(key: SecretKey): String = Base64.encodeToString(key.encoded, Base64.NO_WRAP)

    fun decodeKey(encoded: String): SecretKey =
        SecretKeySpec(Base64.decode(encoded, Base64.NO_WRAP), "AES")
}
