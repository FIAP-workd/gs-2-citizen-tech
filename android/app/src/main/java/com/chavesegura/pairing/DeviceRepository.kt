package com.chavesegura.pairing

import com.chavesegura.communication.B64
import com.chavesegura.data.SecurityLog
import com.chavesegura.data.TrustedDeviceDao
import com.chavesegura.data.TrustedDeviceEntity
import com.chavesegura.domain.AuthorizationMode
import com.chavesegura.domain.DeviceStatus
import com.chavesegura.domain.EventTypes
import com.chavesegura.domain.TrustedDevice
import com.chavesegura.security.DeviceKeyWrapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Segredos do vínculo, decifrados só no momento de conectar. */
class DeviceSecrets(val token: String, val secretKey: ByteArray, val publicKey: ByteArray, val ownDeviceId: String)

/** Cadastro e revogação de computadores pareados (`trusted_devices`). */
class DeviceRepository(
    private val dao: TrustedDeviceDao,
    private val wrapper: DeviceKeyWrapper,
    private val log: SecurityLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observe(): Flow<List<TrustedDevice>> = dao.observeAll().map { rows -> rows.map(::toDomain) }

    suspend fun all(): List<TrustedDevice> = dao.all().map(::toDomain)

    suspend fun get(id: String): TrustedDevice? = dao.byId(id)?.let(::toDomain)

    suspend fun secrets(id: String): DeviceSecrets? {
        val row = dao.byId(id) ?: return null
        return DeviceSecrets(
            token = String(wrapper.unwrap(row.ownTokenWrapped), Charsets.UTF_8),
            secretKey = wrapper.unwrap(row.ownSecretWrapped),
            publicKey = B64.decode(row.ownPublicKey),
            ownDeviceId = row.ownDeviceId,
        )
    }

    suspend fun addFromPairing(pending: PendingPairing, linkId: String): TrustedDevice {
        val entity = TrustedDeviceEntity(
            id = UUID.randomUUID().toString(),
            deviceName = pending.qr.computerName,
            publicKey = pending.qr.extensionPublicKey,
            // Segurança por padrão: começa sempre no modo de confirmação.
            authorizationMode = AuthorizationMode.APPROVAL.wire,
            status = DeviceStatus.ACTIVE.wire,
            createdAt = clock(),
            lastSeen = null,
            linkId = linkId,
            relayUrl = pending.qr.relayWs,
            peerDeviceId = pending.peerDeviceId,
            ownDeviceId = pending.ownDeviceId,
            ownTokenWrapped = wrapper.wrap(pending.ownDeviceToken.toByteArray(Charsets.UTF_8)),
            ownPublicKey = B64.encode(pending.ownPublicKey),
            ownSecretWrapped = wrapper.wrap(pending.ownSecretKey),
            pendingRevocation = false,
        )
        dao.upsert(entity)
        log.record(EventTypes.DEVICE_PAIRED, "success", deviceId = entity.id)
        return toDomain(entity)
    }

    suspend fun setMode(id: String, mode: AuthorizationMode) {
        dao.setMode(id, mode.wire)
        log.record(EventTypes.DEVICE_MODE_CHANGED, mode.wire, deviceId = id)
    }

    /** Revogação local imediata; `pending` indica que falta avisar o relay. */
    suspend fun markRevoked(id: String, pending: Boolean) {
        dao.revoke(id, pending)
        log.record(EventTypes.DEVICE_REVOKED, if (pending) "local" else "confirmed", deviceId = id)
    }

    suspend fun revocationDelivered(id: String) = dao.clearPendingRevocation(id)

    suspend fun touch(id: String) = dao.touch(id, clock())

    suspend fun updateTxSeq(id: String, seq: Long) = dao.updateTxSeq(id, seq)

    suspend fun updateRxSeq(id: String, seq: Long) = dao.updateRxSeq(id, seq)

    suspend fun delete(id: String) = dao.delete(id)

    private fun toDomain(row: TrustedDeviceEntity) = TrustedDevice(
        id = row.id,
        deviceName = row.deviceName,
        peerPublicKey = row.publicKey,
        authorizationMode = AuthorizationMode.fromWire(row.authorizationMode),
        status = DeviceStatus.fromWire(row.status),
        createdAt = row.createdAt,
        lastSeen = row.lastSeen,
        linkId = row.linkId,
        relayUrl = row.relayUrl,
        peerDeviceId = row.peerDeviceId,
        pendingRevocation = row.pendingRevocation,
        lastTxSeq = row.lastTxSeq,
        lastRxSeq = row.lastRxSeq,
    )
}
