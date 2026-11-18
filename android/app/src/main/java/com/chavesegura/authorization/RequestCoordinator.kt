package com.chavesegura.authorization

import com.chavesegura.communication.B64
import com.chavesegura.communication.E2eCrypto
import com.chavesegura.communication.Envelope
import com.chavesegura.communication.IntegrityException
import com.chavesegura.communication.PROTOCOL_VERSION
import com.chavesegura.communication.RelayClient
import com.chavesegura.communication.RelayListener
import com.chavesegura.communication.ReplayException
import com.chavesegura.communication.SecureChannel
import com.chavesegura.data.SecurityLog
import com.chavesegura.domain.AuthorizationMode
import com.chavesegura.domain.Credential
import com.chavesegura.domain.DeviceStatus
import com.chavesegura.domain.Domains
import com.chavesegura.domain.EventTypes
import com.chavesegura.domain.TrustedDevice
import com.chavesegura.pairing.DeviceRepository
import com.chavesegura.security.wipe
import com.chavesegura.vault.AppSettings
import com.chavesegura.vault.SaveOutcome
import com.chavesegura.vault.VaultRepository
import com.chavesegura.vault.VaultSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Pedido aguardando o usuário aprovar ou recusar no celular. */
data class ApprovalRequest(
    val requestId: String,
    val deviceId: String,
    val deviceName: String,
    val hostname: String,
    val candidates: List<CandidateView>,
    val reason: ConfirmReason,
    val createdAt: Long,
)

data class CandidateView(val id: String, val title: String, val username: String, val sensitive: Boolean)

/**
 * Coração da Fase 5: conecta cada computador pareado ao relay, decifra os
 * pedidos, aplica a [AuthorizationPolicy] e responde pelo canal E2E.
 *
 * Falha fechada: cofre bloqueado, domínio diferente, dispositivo revogado,
 * mensagem alterada ou repetida nunca liberam credencial.
 */
class RequestCoordinator(
    private val scope: CoroutineScope,
    private val devices: DeviceRepository,
    private val vault: VaultRepository,
    private val session: VaultSession,
    private val trust: TrustSessions,
    private val settings: AppSettings,
    private val log: SecurityLog,
    private val crypto: E2eCrypto,
    private val http: OkHttpClient = RelayClient.httpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Link(val device: TrustedDevice, val client: RelayClient, val channel: SecureChannel?)

    private val links = ConcurrentHashMap<String, Link>()
    private val answers = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private var running = false
    private var reconnectJob: Job? = null

    private val _online = MutableStateFlow<Set<String>>(emptySet())
    /** Computadores (ids locais) cuja extensão está conectada ao relay agora. */
    val online: StateFlow<Set<String>> = _online.asStateFlow()

    private val _connected = MutableStateFlow<Set<String>>(emptySet())
    val connected: StateFlow<Set<String>> = _connected.asStateFlow()

    private val _approvals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val approvals: StateFlow<List<ApprovalRequest>> = _approvals.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Avisos curtos para a interface (snackbar). */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val _trust = MutableStateFlow<Map<String, Long>>(emptyMap())
    val trustSessions: StateFlow<Map<String, Long>> = _trust.asStateFlow()

    init {
        scope.launch {
            session.unlocked.collect { unlocked ->
                if (unlocked) startTrustForAutomaticDevices() else {
                    trust.clear()
                    denyAllPending()
                }
                _trust.value = trust.snapshot()
            }
        }
    }

    // --------------------------------------------------------- conexões ---

    /** Chamado quando o app vem para a frente. */
    fun start() {
        if (running) return
        running = true
        scope.launch { connectAll() }
        reconnectJob = scope.launch {
            while (true) {
                delay(15_000)
                if (running) connectAll()
                _trust.value = trust.snapshot()
            }
        }
    }

    /** Chamado quando o app sai da frente: sem conexão, o computador recebe `peer_offline`. */
    fun stop() {
        running = false
        reconnectJob?.cancel()
        links.values.forEach { it.client.close() }
        links.clear()
        _connected.value = emptySet()
        _online.value = emptySet()
        denyAllPending()
    }

    private suspend fun connectAll() {
        for (device in devices.all()) {
            val wantsConnection = device.status == DeviceStatus.ACTIVE || device.pendingRevocation
            if (wantsConnection && links[device.id] == null) connect(device)
        }
    }

    private suspend fun connect(device: TrustedDevice) {
        val secrets = runCatching { devices.secrets(device.id) }.getOrNull() ?: return
        val channel = if (device.status == DeviceStatus.ACTIVE) {
            runCatching {
                SecureChannel(
                    crypto, device.linkId, secrets.ownDeviceId, device.peerDeviceId,
                    secrets.publicKey, secrets.secretKey, B64.decode(device.peerPublicKey),
                    initialTxSeq = device.lastTxSeq, initialRxSeq = device.lastRxSeq,
                )
            }.getOrNull()
        } else {
            null
        }
        secrets.secretKey.wipe()
        val listener = LinkListener()
        val client = RelayClient(http, device.relayUrl, secrets.ownDeviceId, secrets.token, listener)
        val link = Link(device, client, channel)
        listener.link = link
        links[device.id] = link
        client.connect()
    }

    /** Encaminha os eventos do OkHttp para corrotinas, já com o vínculo conhecido. */
    private inner class LinkListener : RelayListener {
        lateinit var link: Link

        override fun onAuthenticated(links: List<String>) {
            scope.launch(Dispatchers.Default) { onAuthenticated(link, links) }
        }

        override fun onFrame(frame: JSONObject) {
            scope.launch(Dispatchers.Default) { onFrame(link, frame) }
        }

        override fun onClosed(code: String?) {
            scope.launch(Dispatchers.Default) { onClosed(link, code) }
        }
    }

    private suspend fun onAuthenticated(link: Link, linkIds: List<String>) {
        val device = link.device
        if (device.pendingRevocation) {
            // Revogação feita sem conexão: avisa o relay agora (architecture.md §7.5).
            link.client.send(JSONObject().put("v", PROTOCOL_VERSION).put("type", "link.revoke").put("link_id", device.linkId))
            devices.revocationDelivered(device.id)
            delay(500)
            dropLink(device.id)
            return
        }
        if (device.linkId !in linkIds) {
            revokedRemotely(device)
            return
        }
        devices.touch(device.id)
        _connected.update { it + device.id }
    }

    private suspend fun onClosed(link: Link, code: String?) {
        val device = link.device
        if (links[device.id] === link) dropLink(device.id)
        if (code == "device_revoked" || code == "auth_failed") revokedRemotely(device)
    }

    private fun dropLink(deviceId: String) {
        links.remove(deviceId)?.client?.close()
        _connected.update { it - deviceId }
        _online.update { it - deviceId }
    }

    private suspend fun revokedRemotely(device: TrustedDevice) {
        if (device.status == DeviceStatus.ACTIVE) {
            devices.markRevoked(device.id, pending = false)
            _notices.tryEmit("O computador “${device.deviceName}” foi desconectado.")
        }
        dropLink(device.id)
    }

    /** Revoga localmente na hora; avisa o relay agora ou na próxima conexão. */
    suspend fun revoke(deviceId: String) {
        val link = links[deviceId]
        val device = devices.get(deviceId) ?: return
        val sent = link?.client?.send(
            JSONObject().put("v", PROTOCOL_VERSION).put("type", "link.revoke").put("link_id", device.linkId),
        ) == true
        devices.markRevoked(deviceId, pending = !sent)
        trust.end(deviceId)
        _trust.value = trust.snapshot()
        answers.entries.filter { e -> _approvals.value.any { it.requestId == e.key && it.deviceId == deviceId } }
            .forEach { it.value.complete(null) }
        dropLink(deviceId)
    }

    suspend fun setMode(deviceId: String, mode: AuthorizationMode) {
        devices.setMode(deviceId, mode)
        if (mode == AuthorizationMode.AUTOMATIC && session.isUnlocked) trust.start(deviceId, settings.trustMinutes)
        if (mode == AuthorizationMode.APPROVAL) trust.end(deviceId)
        _trust.value = trust.snapshot()
        links[deviceId]?.let { links[deviceId] = Link(devices.get(deviceId) ?: it.device, it.client, it.channel) }
    }

    fun renewTrust(deviceId: String) {
        if (!session.isUnlocked) return
        trust.start(deviceId, settings.trustMinutes)
        _trust.value = trust.snapshot()
    }

    private suspend fun startTrustForAutomaticDevices() {
        for (device in devices.all()) {
            if (device.status == DeviceStatus.ACTIVE && device.authorizationMode == AuthorizationMode.AUTOMATIC) {
                trust.start(device.id, settings.trustMinutes)
            }
        }
    }

    // -------------------------------------------------------- mensagens ---

    private suspend fun onFrame(link: Link, frame: JSONObject) {
        when (frame.optString("type")) {
            "relay" -> onRelay(link, frame)
            "peer.status" -> {
                val online = frame.optBoolean("online")
                _online.update { if (online) it + link.device.id else it - link.device.id }
            }
            "link.revoked" -> revokedRemotely(link.device)
            "error" -> if (frame.optString("code") == "device_revoked") revokedRemotely(link.device)
        }
    }

    private suspend fun onRelay(link: Link, frame: JSONObject) {
        val channel = link.channel ?: return
        val envelope = Envelope.fromJson(frame) ?: return
        val payload = try {
            channel.open(envelope)
        } catch (e: IntegrityException) {
            log.record(EventTypes.MESSAGE_REJECTED, "integrity", deviceId = link.device.id)
            return
        } catch (e: ReplayException) {
            log.record(EventTypes.MESSAGE_REJECTED, "replay", deviceId = link.device.id)
            return
        }
        // O estado do dispositivo é relido a cada mensagem (pode ter sido revogado).
        val device = devices.get(link.device.id) ?: return
        if (device.status != DeviceStatus.ACTIVE) return
        devices.updateRxSeq(device.id, envelope.seq)
        val body = payload.optJSONObject("body") ?: return
        if (body.optString("request_id") != envelope.corr) return
        when (payload.optString("kind")) {
            "fill.request" -> handleFill(link, device, envelope.corr, body)
            "credential.save" -> handleSave(link, device, envelope.corr, body)
            "fill.ack" -> if (body.optBoolean("filled")) {
                log.record(EventTypes.FILL_DONE, "success", deviceId = device.id)
            }
        }
    }

    private suspend fun send(link: Link, requestId: String, kind: String, body: JSONObject) {
        val channel = link.channel ?: return
        val payload = JSONObject().put("kind", kind).put("body", body.put("request_id", requestId))
        val envelope = channel.seal(payload, requestId)
        devices.updateTxSeq(link.device.id, envelope.seq)
        link.client.send(envelope.toJson())
    }

    private fun fillReply(hostname: String, result: String) = JSONObject()
        .put("result", result)
        .put("hostname", hostname)
        .put("expires_at", clock() + RESPONSE_TTL_MS)

    private suspend fun handleFill(link: Link, device: TrustedDevice, requestId: String, body: JSONObject) {
        val hostname = Domains.normalize(body.optString("hostname"))
        if (hostname == null || !body.optBoolean("top_level", false)) {
            send(link, requestId, "fill.response", fillReply(body.optString("hostname"), "error"))
            return
        }
        if (!session.isUnlocked) {
            log.record(EventTypes.FILL_LOCKED, "locked", deviceId = device.id)
            _notices.tryEmit("${device.deviceName} pediu a senha de $hostname, mas o cofre está bloqueado.")
            send(link, requestId, "fill.response", fillReply(hostname, "locked"))
            return
        }
        val matches = vault.findByHostname(hostname)
        if (matches.isEmpty()) {
            val lookalikes = vault.lookalikeDomains(hostname)
            if (lookalikes.isNotEmpty()) {
                log.record(EventTypes.FILL_BLOCKED_DOMAIN, "blocked", deviceId = device.id)
                _notices.tryEmit("Bloqueado: “$hostname” não é “${lookalikes.first()}”. Pode ser um site falso.")
            }
            send(link, requestId, "fill.response", fillReply(hostname, "not_found"))
            return
        }

        val decision = AuthorizationPolicy.decide(
            PolicyInput(
                deviceStatus = device.status,
                mode = device.authorizationMode,
                vaultUnlocked = session.isUnlocked,
                trustedUntil = trust.trustedUntil(device.id),
                now = clock(),
                candidates = matches.map { Candidate(it.id, it.sensitive) },
            ),
        )
        when (decision) {
            Decision.DeviceRevoked -> return
            Decision.Locked -> send(link, requestId, "fill.response", fillReply(hostname, "locked"))
            Decision.NotFound -> send(link, requestId, "fill.response", fillReply(hostname, "not_found"))
            is Decision.AutoApprove -> {
                val credential = matches.first { it.id == decision.credentialId }
                log.record(EventTypes.FILL_AUTOMATIC, "approved", device.id, credential.id)
                _notices.tryEmit("Preenchido automaticamente: ${credential.title} em ${device.deviceName}.")
                send(link, requestId, "fill.response", approved(hostname, credential, "automatic"))
            }
            is Decision.NeedsConfirmation -> {
                val chosen = askUser(requestId, device, hostname, matches, decision.reason)
                val credential = chosen?.let { id -> vault.findByHostname(hostname).firstOrNull { it.id == id } }
                if (credential == null || !session.isUnlocked) {
                    log.record(EventTypes.FILL_DENIED, "denied", deviceId = device.id)
                    send(link, requestId, "fill.response", fillReply(hostname, if (session.isUnlocked) "denied" else "locked"))
                } else {
                    log.record(EventTypes.FILL_APPROVED, "approved", device.id, credential.id)
                    send(link, requestId, "fill.response", approved(hostname, credential, "confirmation"))
                }
            }
        }
    }

    private fun approved(hostname: String, credential: Credential, mode: String) = fillReply(hostname, "approved")
        .put("mode", mode)
        .put("title", credential.title)
        .put("credential", JSONObject().put("username", credential.username).put("password", credential.password))

    private suspend fun askUser(
        requestId: String,
        device: TrustedDevice,
        hostname: String,
        matches: List<Credential>,
        reason: ConfirmReason,
    ): String? {
        if (answers.containsKey(requestId)) return null // pedido duplicado: processado uma vez
        val deferred = CompletableDeferred<String?>()
        answers[requestId] = deferred
        val request = ApprovalRequest(
            requestId = requestId,
            deviceId = device.id,
            deviceName = device.deviceName,
            hostname = hostname,
            candidates = matches.map { CandidateView(it.id, it.title, it.username, it.sensitive) },
            reason = reason,
            createdAt = clock(),
        )
        _approvals.update { it + request }
        try {
            return withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() }
        } finally {
            answers.remove(requestId)
            _approvals.update { list -> list.filterNot { it.requestId == requestId } }
        }
    }

    /** Resposta da tela de aprovação: id da credencial escolhida, ou null para recusar. */
    fun answer(requestId: String, credentialId: String?) {
        answers[requestId]?.complete(credentialId)
    }

    private fun denyAllPending() {
        answers.values.forEach { it.complete(null) }
    }

    private suspend fun handleSave(link: Link, device: TrustedDevice, requestId: String, body: JSONObject) {
        val hostname = Domains.normalize(body.optString("hostname"))
        val username = body.optString("username").trim()
        val password = body.optString("password")
        suspend fun reply(result: String) = send(
            link, requestId, "credential.save.result",
            JSONObject().put("result", result).put("hostname", hostname ?: body.optString("hostname")),
        )
        if (hostname == null || username.isEmpty() || password.isEmpty() || password.length > 256) {
            reply("error")
            return
        }
        if (!session.isUnlocked) {
            _notices.tryEmit("${device.deviceName} criou uma senha para $hostname. Desbloqueie o cofre para salvá-la.")
            reply("locked")
            return
        }
        try {
            val outcome = vault.saveFromBrowser(hostname, username, password, device.id)
            _notices.tryEmit(
                if (outcome == SaveOutcome.SAVED) "Nova senha salva: $username em $hostname."
                else "Senha atualizada: $username em $hostname.",
            )
            reply(if (outcome == SaveOutcome.SAVED) "saved" else "updated")
        } catch (e: Exception) {
            reply("error")
        }
    }

    companion object {
        const val RESPONSE_TTL_MS = 30_000L
        const val APPROVAL_TIMEOUT_MS = 60_000L
    }
}
