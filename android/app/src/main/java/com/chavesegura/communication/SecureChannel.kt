package com.chavesegura.communication

import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

class IntegrityException : Exception("mensagem alterada ou de origem desconhecida")

/**
 * Canal E2E com um computador pareado (lado Android = servidor do crypto_kx).
 * O relay vê só o cabeçalho; qualquer alteração dele invalida a mensagem.
 */
class SecureChannel(
    private val crypto: E2eCrypto,
    val linkId: String,
    val ownDeviceId: String,
    val peerDeviceId: String,
    ownPublicKey: ByteArray,
    ownSecretKey: ByteArray,
    peerPublicKey: ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
    initialTxSeq: Long = 0L,
    initialRxSeq: Long = -1L,
) {
    private val rx: ByteArray
    private val tx: ByteArray
    private val guard = ReplayGuard(clock, initialLastSeq = initialRxSeq)
    private val random = SecureRandom()

    /** Semeado com o máximo entre relógio e último seq persistido: nunca regride. */
    private var seq: Long = maxOf(clock(), initialTxSeq)

    val currentTxSeq: Long
        @Synchronized get() = seq

    val currentRxSeq: Long
        get() = guard.currentLastSeq

    init {
        val keys = crypto.serverSessionKeys(ownPublicKey, ownSecretKey, peerPublicKey)
        rx = keys.first
        tx = keys.second
    }

    @Synchronized
    fun seal(payload: JSONObject, corr: String): Envelope {
        seq += 1
        val msgId = UUID.randomUUID().toString()
        val ts = clock()
        val nonce = ByteArray(24).also(random::nextBytes)
        val aad = Envelope.aadFor(msgId, linkId, ownDeviceId, peerDeviceId, ts, seq, corr)
        val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
        val ciphertext = crypto.seal(tx, nonce, plaintext, aad)
        plaintext.fill(0)
        return Envelope(msgId, linkId, ownDeviceId, peerDeviceId, ts, seq, corr, B64.encode(nonce), B64.encode(ciphertext))
    }

    /** Valida remetente, vínculo, janela, repetição e integridade; só então devolve o payload. */
    @Synchronized
    fun open(envelope: Envelope): JSONObject {
        if (envelope.from != peerDeviceId || envelope.to != ownDeviceId || envelope.linkId != linkId) {
            throw IntegrityException()
        }
        guard.check(envelope)
        val opened: ByteArray? = try {
            crypto.open(rx, B64.decode(envelope.nonce), B64.decode(envelope.ciphertext), envelope.aad())
        } catch (e: IllegalArgumentException) {
            null
        }
        val plain = opened ?: throw IntegrityException()
        guard.accept(envelope)
        try {
            return JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw IntegrityException()
        } finally {
            plain.fill(0)
        }
    }
}
