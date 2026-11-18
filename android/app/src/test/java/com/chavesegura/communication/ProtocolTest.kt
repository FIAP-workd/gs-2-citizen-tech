package com.chavesegura.communication

import com.chavesegura.pairing.QrPayload
import com.chavesegura.vault.UnlockThrottle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Vetores compartilhados com a extensão e com a implementação de referência em Python. */
private val vectors: JSONObject by lazy {
    val file = listOf(File("../../docs/test-vectors/e2e-v1.json"), File("docs/test-vectors/e2e-v1.json"))
        .first { it.exists() }
    JSONObject(file.readText())
}

class EnvelopeTest {
    @Test
    fun `AAD tem o formato canonico do contrato`() {
        val envelope = Envelope.fromJson(vectors.getJSONObject("envelope"))
        assertNotNull(envelope)
        assertEquals(vectors.getString("aad"), String(envelope!!.aad(), Charsets.US_ASCII))
    }

    @Test
    fun `envelope ida e volta em JSON`() {
        val original = Envelope.fromJson(vectors.getJSONObject("envelope"))!!
        assertEquals(original, Envelope.fromJson(original.toJson()))
    }

    @Test
    fun `envelope sem campo obrigatorio e invalido`() {
        val json = JSONObject(vectors.getJSONObject("envelope").toString()).apply { remove("nonce") }
        assertNull(Envelope.fromJson(json))
    }

    @Test
    fun `codigo de verificacao igual ao da extensao`() {
        val digest = "59191aacc0ba8580ed5d7c28c94cffde514bd416bc8781bc54d01c9a9d998995"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(vectors.getString("sas"), Sas.fromDigest(digest))
    }
}

class ReplayGuardTest {
    private fun envelope(msgId: String, ts: Long, seq: Long) =
        Envelope(msgId, "L", "E", "A", ts, seq, "", "n", "c")

    @Test(expected = ReplayException::class)
    fun `mensagem repetida e recusada`() {
        val guard = ReplayGuard { 1_000_000 }
        val e = envelope("m1", 1_000_000, 1)
        guard.check(e)
        guard.accept(e)
        guard.check(e)
    }

    @Test(expected = ReplayException::class)
    fun `mensagem antiga e recusada`() {
        ReplayGuard { 1_000_000 }.check(envelope("m1", 1_000_000 - 61_000, 1))
    }

    @Test(expected = ReplayException::class)
    fun `sequencia que volta e recusada`() {
        val guard = ReplayGuard { 1_000_000 }
        guard.accept(envelope("m1", 1_000_000, 5))
        guard.check(envelope("m2", 1_000_000, 5))
    }

    @Test
    fun `mensagens novas em ordem sao aceitas`() {
        val guard = ReplayGuard { 1_000_000 }
        listOf(envelope("m1", 1_000_000, 1), envelope("m2", 1_000_010, 2)).forEach {
            guard.check(it)
            guard.accept(it)
        }
        assertEquals(2L, guard.currentLastSeq)
    }

    @Test(expected = ReplayException::class)
    fun `sequencia persistida entre reinicios impede replay`() {
        val restored = ReplayGuard(clock = { 1_000_000 }, initialLastSeq = 42L)
        restored.check(envelope("m-old", 1_000_000, 42L))
    }

    @Test
    fun `sequencia maior que a persistida e aceita apos reinicio`() {
        val restored = ReplayGuard(clock = { 1_000_000 }, initialLastSeq = 42L)
        val next = envelope("m-new", 1_000_000, 43L)
        restored.check(next)
        restored.accept(next)
        assertEquals(43L, restored.currentLastSeq)
    }
}

class QrPayloadTest {
    private val valid = JSONObject()
        .put("v", 1).put("t", "cs-pair").put("relay", "ws://10.0.2.2:8000/v1/ws")
        .put("pid", "4f6c1c1e-7d0a-4c8e-9b8e-2d9b5a3f0c11").put("tok", "abc")
        .put("pk", vectors.getJSONObject("extension").getString("public_key"))
        .put("name", "Chrome — Notebook da Ana")

    @Test
    fun `le o QR da extensao`() {
        val qr = QrPayload.parse(valid.toString())!!
        assertEquals("http://10.0.2.2:8000", qr.httpBase)
        assertEquals("Chrome — Notebook da Ana", qr.computerName)
    }

    @Test
    fun `recusa QR que nao e de pareamento`() {
        assertNull(QrPayload.parse("https://exemplo.com"))
        assertNull(QrPayload.parse(JSONObject(valid.toString()).put("t", "outro").toString()))
        assertNull(QrPayload.parse(JSONObject(valid.toString()).put("pk", "curta").toString()))
        assertNull(QrPayload.parse(JSONObject(valid.toString()).put("relay", "http://x").toString()))
    }
}

class UnlockThrottleTest {
    @Test
    fun `atraso cresce depois de cinco erros`() {
        assertEquals(0L, UnlockThrottle.lockoutMillis(4))
        assertEquals(30_000L, UnlockThrottle.lockoutMillis(5))
        assertEquals(60_000L, UnlockThrottle.lockoutMillis(6))
    }
}
