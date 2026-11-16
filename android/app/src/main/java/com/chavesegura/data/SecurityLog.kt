package com.chavesegura.data

import com.chavesegura.domain.SecurityEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Histórico de segurança. Só metadados: nunca senhas, tokens ou conteúdo de mensagens. */
class SecurityLog(private val dao: SecurityEventDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun record(type: String, result: String, deviceId: String? = null, credentialId: String? = null) {
        dao.insert(SecurityEventEntity(UUID.randomUUID().toString(), type, deviceId, credentialId, clock(), result))
    }

    fun observe(): Flow<List<SecurityEvent>> = dao.observeRecent().map { rows ->
        rows.map { SecurityEvent(it.id, it.eventType, it.deviceId, it.credentialId, it.timestamp, it.result) }
    }
}
