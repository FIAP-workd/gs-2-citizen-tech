package com.chavesegura.security

import java.security.SecureRandom

class WrongPasswordException : Exception("senha mestra incorreta")

/** Material criptográfico gravado em `vault_metadata` (nada aqui abre o cofre sozinho). */
class VaultKeyMaterial(
    val salt: ByteArray,
    val params: KdfParams,
    /** wrap2 = Keystore(AES-GCM(KEK, VK)) */
    val wrappedVaultKey: ByteArray,
)

/**
 * Hierarquia de chaves do ADR 0001 §2:
 *
 *     KEK   = Argon2id(senha mestra, salt)        -- só em memória
 *     VK    = 32 bytes aleatórios                 -- vault key
 *     wrap1 = AES-256-GCM(KEK, VK)
 *     wrap2 = AES-256-GCM(chave do Keystore, wrap1)
 *
 * O desbloqueio só acontece se a tag GCM de wrap1 conferir: senha errada
 * nunca produz uma chave "quase certa".
 */
class VaultCrypto(
    private val kdf: PasswordKdf,
    private val deviceWrapper: DeviceKeyWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    fun create(password: CharArray, params: KdfParams = KdfParams.V1): Pair<VaultKeyMaterial, ByteArray> {
        val salt = ByteArray(16).also(random::nextBytes)
        val vaultKey = ByteArray(32).also(random::nextBytes)
        val kek = deriveKek(password, salt, params)
        try {
            val wrap1 = AesGcm.encrypt(kek, vaultKey, AAD_VAULT_KEY)
            return VaultKeyMaterial(salt, params, deviceWrapper.wrap(wrap1)) to vaultKey
        } finally {
            kek.wipe()
        }
    }

    /** Devolve a VK ou lança [WrongPasswordException]. */
    fun unlock(password: CharArray, material: VaultKeyMaterial): ByteArray {
        val wrap1 = deviceWrapper.unwrap(material.wrappedVaultKey)
        val kek = deriveKek(password, material.salt, material.params)
        try {
            return AesGcm.decrypt(kek, wrap1, AAD_VAULT_KEY)
        } catch (e: CryptoFailure) {
            throw WrongPasswordException()
        } finally {
            kek.wipe()
        }
    }

    private fun deriveKek(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val bytes = password.toUtf8Bytes()
        try {
            return kdf.derive(bytes, salt, params)
        } finally {
            bytes.wipe()
        }
    }

    companion object {
        val AAD_VAULT_KEY = "chave-segura/vk/v1".toByteArray()
    }
}

/**
 * Cifra cada campo de credencial com a VK. A AAD amarra o ciphertext ao
 * registro e ao campo (threat-model C3): copiar a senha de uma linha para
 * outra, ou trocar usuário por senha, faz a decifragem falhar.
 */
class FieldCipher(private val vaultKey: ByteArray) {
    fun seal(credentialId: String, field: String, value: String): ByteArray =
        AesGcm.encrypt(vaultKey, value.toByteArray(Charsets.UTF_8), aad(credentialId, field))

    fun open(credentialId: String, field: String, blob: ByteArray): String {
        val plain = AesGcm.decrypt(vaultKey, blob, aad(credentialId, field))
        try {
            return String(plain, Charsets.UTF_8)
        } finally {
            plain.wipe()
        }
    }

    private fun aad(credentialId: String, field: String) = "$credentialId|$field|$SCHEMA_VERSION".toByteArray()

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
