package com.chavesegura.pairing

import com.chavesegura.communication.B64
import com.chavesegura.communication.E2eCrypto
import com.chavesegura.communication.PROTOCOL_VERSION
import com.chavesegura.communication.Sas
import com.chavesegura.security.wipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Conteúdo do QR Code exibido pela extensão (contrato §3.2). */
data class QrPayload(
    val relayWs: String,
    val pairingId: String,
    val pairingToken: String,
    val extensionPublicKey: String,
    val computerName: String,
) {
    /** `ws://host:8000/v1/ws` → `http://host:8000` */
    val httpBase: String
        get() = relayWs.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").removeSuffix("/v1/ws")

    companion object {
        fun parse(text: String): QrPayload? = try {
            val json = JSONObject(text.trim())
            if (json.optInt("v") != PROTOCOL_VERSION || json.optString("t") != "cs-pair") {
                null
            } else {
                val relay = json.getString("relay")
                val publicKey = json.getString("pk")
                if (!(relay.startsWith("ws://") || relay.startsWith("wss://"))) null
                else if (B64.decode(publicKey).size != 32) null
                else QrPayload(relay, json.getString("pid"), json.getString("tok"), publicKey, json.getString("name").take(64))
            }
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

class PairingException(message: String) : Exception(message)

/** Resultado da leitura do QR: falta o usuário conferir o código e confirmar. */
class PendingPairing(
    val qr: QrPayload,
    val ownDeviceId: String,
    val ownDeviceToken: String,
    val ownPublicKey: ByteArray,
    val ownSecretKey: ByteArray,
    val peerDeviceId: String,
    val sas: String,
) {
    fun wipe() = ownSecretKey.wipe()
}

/** Chamadas REST de pareamento (contrato §3.3 e §3.4). */
class PairingClient(private val http: OkHttpClient, private val crypto: E2eCrypto) {
    private val json = "application/json".toMediaType()

    suspend fun claim(qr: QrPayload, deviceName: String): PendingPairing = withContext(Dispatchers.IO) {
        val (pk, sk) = crypto.keyPair()
        val body = JSONObject()
            .put("v", PROTOCOL_VERSION)
            .put("pairing_token", qr.pairingToken)
            .put("device_name", deviceName)
            .put("public_key", B64.encode(pk))
        val response = post("${qr.httpBase}/v1/pairing/sessions/${qr.pairingId}/claim", body, null)
        val peer = response.getJSONObject("peer")
        // A chave da extensão vale pelo QR (canal físico), não pelo servidor (threat-model P2).
        if (peer.getString("public_key") != qr.extensionPublicKey) {
            sk.wipe()
            throw PairingException("A identidade do computador não confere com o QR Code. Pareamento cancelado por segurança.")
        }
        PendingPairing(
            qr = qr,
            ownDeviceId = response.getString("device_id"),
            ownDeviceToken = response.getString("device_token"),
            ownPublicKey = pk,
            ownSecretKey = sk,
            peerDeviceId = peer.getString("device_id"),
            sas = Sas.code(crypto, B64.decode(qr.extensionPublicKey), pk),
        )
    }

    /** Devolve o `link_id` quando aceito, ou null quando recusado. */
    suspend fun confirm(pending: PendingPairing, accepted: Boolean): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("v", PROTOCOL_VERSION).put("accepted", accepted)
        val response = post(
            "${pending.qr.httpBase}/v1/pairing/sessions/${pending.qr.pairingId}/confirm",
            body,
            pending.ownDeviceToken,
        )
        if (accepted) response.getString("link_id") else null
    }

    private fun post(url: String, body: JSONObject, bearer: String?): JSONObject {
        val builder = Request.Builder().url(url).post(body.toString().toRequestBody(json))
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        try {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                if (!response.isSuccessful) throw PairingException(messageFor(parsed.optString("code")))
                return parsed
            }
        } catch (e: IOException) {
            throw PairingException("Não foi possível falar com o servidor. Celular e computador estão na mesma rede?")
        } catch (e: IllegalArgumentException) {
            throw PairingException("Endereço do servidor inválido no QR Code.")
        }
    }

    private fun messageFor(code: String) = when (code) {
        "pairing_expired" -> "O código expirou. Gere um novo no computador."
        "pairing_used" -> "Este código já foi usado. Gere um novo no computador."
        "pairing_not_found" -> "Código de pareamento inválido."
        "rate_limited" -> "Muitas tentativas. Aguarde um minuto."
        else -> "O servidor recusou o pareamento ($code)."
    }
}
