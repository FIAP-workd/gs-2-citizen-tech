package com.chavesegura.communication

import com.chavesegura.security.Sodium

/** Primitivas do canal E2E (ADR 0001 §4). Interface para isolar a libsodium. */
interface E2eCrypto {
    /** Par X25519 de `crypto_kx` (público, secreto). */
    fun keyPair(): Pair<ByteArray, ByteArray>

    /** O Android é o "servidor" do crypto_kx; a extensão é o "cliente". Devolve (rx, tx). */
    fun serverSessionKeys(serverPk: ByteArray, serverSk: ByteArray, clientPk: ByteArray): Pair<ByteArray, ByteArray>

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** null se a tag não conferir (mensagem alterada). */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray?

    /** BLAKE2b de 32 bytes (`crypto_generichash`), usado no código de verificação. */
    fun hash32(input: ByteArray): ByteArray
}

object SodiumE2eCrypto : E2eCrypto {
    private const val ABYTES = 16

    override fun keyPair(): Pair<ByteArray, ByteArray> {
        val pk = ByteArray(32)
        val sk = ByteArray(32)
        check(Sodium.lazy.cryptoKxKeypair(pk, sk)) { "crypto_kx_keypair falhou" }
        return pk to sk
    }

    override fun serverSessionKeys(serverPk: ByteArray, serverSk: ByteArray, clientPk: ByteArray): Pair<ByteArray, ByteArray> {
        val rx = ByteArray(32)
        val tx = ByteArray(32)
        check(Sodium.lazy.cryptoKxServerSessionKeys(rx, tx, serverPk, serverSk, clientPk)) { "chave pública inválida" }
        return rx to tx
    }

    override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + ABYTES)
        val outLen = LongArray(1)
        val ok = Sodium.lazy.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            out, outLen, plaintext, plaintext.size.toLong(), aad, aad.size.toLong(), null, nonce, key,
        )
        check(ok) { "falha ao cifrar" }
        return out
    }

    override fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? {
        if (ciphertext.size < ABYTES) return null
        val out = ByteArray(ciphertext.size - ABYTES)
        val outLen = LongArray(1)
        val ok = Sodium.lazy.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            out, outLen, null, ciphertext, ciphertext.size.toLong(), aad, aad.size.toLong(), nonce, key,
        )
        return if (ok) out else null
    }

    override fun hash32(input: ByteArray): ByteArray {
        val out = ByteArray(32)
        check(Sodium.lazy.cryptoGenericHash(out, out.size, input, input.size.toLong(), null, 0)) { "generichash falhou" }
        return out
    }
}

/** Código curto de verificação (SAS) de 6 dígitos, igual nas duas telas. */
object Sas {
    private val LABEL = "chave-segura/sas/v1".toByteArray()

    fun code(crypto: E2eCrypto, extensionPk: ByteArray, androidPk: ByteArray): String =
        fromDigest(crypto.hash32(LABEL + extensionPk + androidPk))

    /** Primeiros 4 bytes (big-endian) mod 1.000.000, com zeros à esquerda. */
    fun fromDigest(digest: ByteArray): String {
        val value = ((digest[0].toLong() and 0xff) shl 24) or
            ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or
            (digest[3].toLong() and 0xff)
        return (value % 1_000_000).toString().padStart(6, '0')
    }
}
