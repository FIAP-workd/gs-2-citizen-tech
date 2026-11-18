package com.chavesegura.communication

import org.json.JSONObject
import java.util.Base64

const val PROTOCOL_VERSION = 1
const val TS_WINDOW_MS = 60_000L

object B64 {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(text: String): ByteArray = decoder.decode(text)
}

/** Envelope E2E do contrato (§4.2). O cabeçalho vai em claro, mas autenticado (AAD). */
data class Envelope(
    val msgId: String,
    val linkId: String,
    val from: String,
    val to: String,
    val ts: Long,
    val seq: Long,
    val corr: String,
    val nonce: String,
    val ciphertext: String,
) {
    /** `cs/1|msg_id|link_id|from|to|ts|seq|corr` */
    fun aad(): ByteArray = aadFor(msgId, linkId, from, to, ts, seq, corr)

    fun toJson(): JSONObject = JSONObject()
        .put("v", PROTOCOL_VERSION)
        .put("type", "relay")
        .put("msg_id", msgId)
        .put("link_id", linkId)
        .put("from", from)
        .put("to", to)
        .put("ts", ts)
        .put("seq", seq)
        .put("corr", corr)
        .put("nonce", nonce)
        .put("ciphertext", ciphertext)

    companion object {
        fun aadFor(msgId: String, linkId: String, from: String, to: String, ts: Long, seq: Long, corr: String) =
            "cs/$PROTOCOL_VERSION|$msgId|$linkId|$from|$to|$ts|$seq|$corr".toByteArray(Charsets.US_ASCII)

        /** Lê um frame `relay`; devolve null se faltar campo (mensagem inválida). */
        fun fromJson(json: JSONObject): Envelope? = try {
            Envelope(
                msgId = json.getString("msg_id"),
                linkId = json.getString("link_id"),
                from = json.getString("from"),
                to = json.getString("to"),
                ts = json.getLong("ts"),
                seq = json.getLong("seq"),
                corr = json.optString("corr", ""),
                nonce = json.getString("nonce"),
                ciphertext = json.getString("ciphertext"),
            )
        } catch (e: org.json.JSONException) {
            null
        }
    }
}

class ReplayException(message: String) : Exception(message)

/** Anti-replay: msg_id único, janela de ±60 s e seq estritamente crescente. */
class ReplayGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    initialLastSeq: Long = -1L,
) {
    private var lastSeq = initialLastSeq
    private val seen = LinkedHashMap<String, Long>()

    val currentLastSeq: Long
        @Synchronized get() = lastSeq

    @Synchronized
    fun check(envelope: Envelope) {
        val now = clock()
        if (kotlin.math.abs(now - envelope.ts) > TS_WINDOW_MS) throw ReplayException("fora da janela de tempo")
        if (seen.containsKey(envelope.msgId)) throw ReplayException("mensagem repetida")
        if (envelope.seq <= lastSeq) throw ReplayException("sequência inválida")
    }

    @Synchronized
    fun accept(envelope: Envelope) {
        val now = clock()
        lastSeq = envelope.seq
        seen.entries.removeAll { now - it.value > TS_WINDOW_MS }
        seen[envelope.msgId] = now
    }
}
