package com.chavesegura.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Falha de criptografia: a operação é interrompida e nenhum dado parcial é devolvido. */
class CryptoFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AES-256-GCM com nonce aleatório de 12 bytes por operação (ADR 0001 §3).
 * Formato: `nonce || ciphertext || tag`.
 */
object AesGcm {
    const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private val random = SecureRandom()

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        require(key.size == 32) { "AES-256 exige chave de 32 bytes" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad != null) cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, blob: ByteArray, aad: ByteArray?): ByteArray {
        if (blob.size < NONCE_BYTES + TAG_BYTES) throw CryptoFailure("dado cifrado curto demais")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            if (aad != null) cipher.updateAAD(aad)
            return cipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            throw CryptoFailure("verificação de integridade falhou", e)
        }
    }
}

/** Apaga o conteúdo de um buffer sensível. */
fun ByteArray.wipe() = fill(0)

fun CharArray.wipe() = fill('\u0000')

/** Converte a senha para UTF-8 sem criar uma String (que não pode ser apagada). */
fun CharArray.toUtf8Bytes(): ByteArray {
    val buffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(this))
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (buffer.hasArray()) buffer.array().fill(0)
    return bytes
}
