package com.chavesegura.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class BiometricInvalidatedException : Exception("biometria alterada; configure de novo")

/**
 * Atalho de desbloqueio por biometria (Fase 2, threat-model C8).
 *
 * Uma segunda cópia da VK é cifrada por uma chave do Keystore que só pode ser
 * usada depois de uma autenticação biométrica forte, e que é invalidada quando
 * uma nova digital é cadastrada. A senha mestra continua existindo e sendo
 * necessária para criar o cofre e reativar a biometria.
 */
class BiometricVaultKey(private val alias: String = "chave_segura_biometric_key") {

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun key(create: Boolean): SecretKey? {
        (androidKeyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(builder.build())
        return generator.generateKey()
    }

    /** Cipher para cifrar a VK; só funciona depois do BiometricPrompt. */
    fun encryptCipher(): Cipher {
        delete()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        return cipher
    }

    /** Cipher para decifrar a cópia biométrica (formato `iv || ct`). */
    fun decryptCipher(blob: ByteArray): Cipher {
        val key = key(create = false) ?: throw BiometricInvalidatedException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, AesGcm.NONCE_BYTES))
        } catch (e: KeyPermanentlyInvalidatedException) {
            delete()
            throw BiometricInvalidatedException()
        }
        return cipher
    }

    fun seal(authenticated: Cipher, vaultKey: ByteArray): ByteArray = authenticated.iv + authenticated.doFinal(vaultKey)

    fun open(authenticated: Cipher, blob: ByteArray): ByteArray =
        authenticated.doFinal(blob, AesGcm.NONCE_BYTES, blob.size - AesGcm.NONCE_BYTES)

    fun delete() {
        runCatching { androidKeyStore().deleteEntry(alias) }
    }
}
