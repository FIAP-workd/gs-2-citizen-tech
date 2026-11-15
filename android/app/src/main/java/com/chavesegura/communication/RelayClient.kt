package com.chavesegura.communication

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Eventos de uma conexão com o relay. */
interface RelayListener {
    fun onAuthenticated(links: List<String>)

    fun onFrame(frame: JSONObject)

    /** `code` é o código de erro do relay (ex.: `device_revoked`) ou null para queda de rede. */
    fun onClosed(code: String?)
}

/**
 * Conexão WebSocket autenticada de um vínculo (contrato §4). O primeiro frame
 * é sempre `auth`; ping a cada 20 s mantém a conexão viva.
 */
class RelayClient(
    private val http: OkHttpClient,
    private val url: String,
    private val deviceId: String,
    private val deviceToken: String,
    private val listener: RelayListener,
) {
    private var socket: WebSocket? = null

    @Volatile
    var authenticated = false
        private set

    @Volatile
    private var lastError: String? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    JSONObject()
                        .put("v", PROTOCOL_VERSION)
                        .put("type", "auth")
                        .put("device_id", deviceId)
                        .put("device_token", deviceToken)
                        .toString(),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (frame.optString("type")) {
                    "auth.ok" -> {
                        authenticated = true
                        val array = frame.optJSONArray("links")
                        listener.onAuthenticated(List(array?.length() ?: 0) { array!!.getString(it) })
                    }
                    "error" -> {
                        if (!authenticated) lastError = frame.optString("code")
                        listener.onFrame(frame)
                    }
                    else -> listener.onFrame(frame)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                authenticated = false
                listener.onClosed(lastError)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                authenticated = false
                listener.onClosed(lastError)
            }
        })
    }

    fun send(frame: JSONObject): Boolean = authenticated && socket?.send(frame.toString()) == true

    fun close() {
        authenticated = false
        socket?.close(1000, null)
        socket = null
    }

    companion object {
        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
