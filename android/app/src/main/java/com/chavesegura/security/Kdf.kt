package com.chavesegura.security

import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong

/** Parâmetros versionados do Argon2id, gravados em `vault_metadata` (permite migrar). */
data class KdfParams(val version: Int, val opsLimit: Long, val memLimitBytes: Long) {
    companion object {
        /** ADR 0001: 64 MiB, 3 iterações, paralelismo 1. */
        val V1 = KdfParams(version = 1, opsLimit = 3, memLimitBytes = 64L * 1024 * 1024)
    }
}

fun interface PasswordKdf {
    /** Deriva 32 bytes (KEK) a partir da senha mestra e do salt. */
    fun derive(password: ByteArray, salt: ByteArray, params: KdfParams): ByteArray
}

/** Argon2id via libsodium (`crypto_pwhash` com ALG_ARGON2ID13). */
class Argon2idKdf : PasswordKdf {
    override fun derive(password: ByteArray, salt: ByteArray, params: KdfParams): ByteArray {
        val out = ByteArray(32)
        val ok = Sodium.lazy.cryptoPwHash(
            out,
            out.size,
            password,
            password.size,
            salt,
            params.opsLimit,
            NativeLong(params.memLimitBytes),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        if (!ok) throw CryptoFailure("falha ao derivar a chave (memória insuficiente?)")
        return out
    }
}
