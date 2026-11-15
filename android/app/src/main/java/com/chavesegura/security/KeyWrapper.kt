package com.chavesegura.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Segunda camada de proteção com uma chave presa ao aparelho (ADR 0001 §2).
 * Copiar o banco para outro aparelho não basta para atacar a senha mestra.
 */
interface DeviceKeyWrapper {
    fun wrap(plain: ByteArray): ByteArray

    fun unwrap(blob: ByteArray): ByteArray
}

private const val KEYSTORE = "AndroidKeyStore"

internal fun androidKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

/** Chave AES-256 não exportável do Android Keystore (StrongBox quando disponível). */
class KeystoreWrapper(private val alias: String = "chave_segura_device_key") : DeviceKeyWrapper {

    private fun key(): SecretKey {
        (androidKeyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return try {
            generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (e: Exception) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, AesGcm.NONCE_BYTES))
            return cipher.doFinal(blob, AesGcm.NONCE_BYTES, blob.size - AesGcm.NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            throw CryptoFailure("chave do aparelho não confere", e)
        }
    }
}
