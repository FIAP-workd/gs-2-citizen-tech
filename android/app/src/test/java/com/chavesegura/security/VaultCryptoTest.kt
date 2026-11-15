package com.chavesegura.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Na JVM não há libsodium nem Android Keystore: os testes usam PBKDF2 no lugar
 * do Argon2id e um "wrapper" sem hardware. A lógica do cofre é a mesma.
 */
private val testKdf = PasswordKdf { password, salt, _ ->
    val chars = String(password, Charsets.UTF_8).toCharArray()
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(chars, salt, 1_000, 256))
        .encoded
}

private object NoHardwareWrapper : DeviceKeyWrapper {
    private val key = ByteArray(32) { 7 }
    override fun wrap(plain: ByteArray) = AesGcm.encrypt(key, plain, null)
    override fun unwrap(blob: ByteArray) = AesGcm.decrypt(key, blob, null)
}

/** Simula outro aparelho: a chave do "Keystore" é diferente. */
private object OtherDeviceWrapper : DeviceKeyWrapper {
    private val key = ByteArray(32) { 9 }
    override fun wrap(plain: ByteArray) = AesGcm.encrypt(key, plain, null)
    override fun unwrap(blob: ByteArray) = AesGcm.decrypt(key, blob, null)
}

class VaultCryptoTest {
    private val crypto = VaultCrypto(testKdf, NoHardwareWrapper)

    @Test
    fun `senha correta recupera a mesma chave do cofre`() {
        val (material, vk) = crypto.create("frase longa e secreta".toCharArray())
        assertArrayEquals(vk, crypto.unlock("frase longa e secreta".toCharArray(), material))
    }

    @Test(expected = WrongPasswordException::class)
    fun `senha mestra incorreta nao desbloqueia`() {
        val (material, _) = crypto.create("frase longa e secreta".toCharArray())
        crypto.unlock("frase longa e secreta!".toCharArray(), material)
    }

    @Test(expected = CryptoFailure::class)
    fun `banco copiado para outro aparelho nao abre nem com a senha certa`() {
        val (material, _) = crypto.create("frase longa e secreta".toCharArray())
        VaultCrypto(testKdf, OtherDeviceWrapper).unlock("frase longa e secreta".toCharArray(), material)
    }

    @Test
    fun `cada cofre tem salt e chave proprios`() {
        val (a, vkA) = crypto.create("mesma senha mestra".toCharArray())
        val (b, vkB) = crypto.create("mesma senha mestra".toCharArray())
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(vkA.contentEquals(vkB))
        assertEquals(KdfParams.V1, a.params)
    }

    @Test
    fun `parametros do Argon2id seguem o ADR`() {
        assertEquals(64L * 1024 * 1024, KdfParams.V1.memLimitBytes)
        assertEquals(3L, KdfParams.V1.opsLimit)
    }
}

class FieldCipherTest {
    private val cipher = FieldCipher(ByteArray(32) { it.toByte() })

    @Test
    fun `cifra e decifra um campo`() {
        val blob = cipher.seal("id-1", "password", "Senha-Ficticia-123")
        assertEquals("Senha-Ficticia-123", cipher.open("id-1", "password", blob))
    }

    @Test
    fun `o texto aberto nao aparece no dado gravado`() {
        val blob = cipher.seal("id-1", "password", "Senha-Ficticia-123")
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("Senha-Ficticia"))
    }

    @Test
    fun `nonce novo a cada operacao`() {
        assertNotEquals(
            cipher.seal("id-1", "password", "x").toList(),
            cipher.seal("id-1", "password", "x").toList(),
        )
    }

    @Test(expected = CryptoFailure::class)
    fun `senha copiada para outra credencial e detectada`() {
        val blob = cipher.seal("id-1", "password", "segredo")
        cipher.open("id-2", "password", blob)
    }

    @Test(expected = CryptoFailure::class)
    fun `troca de campo usuario por senha e detectada`() {
        val blob = cipher.seal("id-1", "username", "ana")
        cipher.open("id-1", "password", blob)
    }

    @Test(expected = CryptoFailure::class)
    fun `dado alterado e detectado`() {
        val blob = cipher.seal("id-1", "password", "segredo")
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        cipher.open("id-1", "password", blob)
    }
}
