package com.chavesegura.domain

/** Credencial já decifrada: existe só em memória enquanto o cofre está aberto. */
data class Credential(
    val id: String,
    val title: String,
    val username: String,
    val password: String,
    val websiteDomain: String,
    val categoryId: String?,
    val notes: String,
    val sensitive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun toString(): String = "Credential(id=$id, title=$title, domain=$websiteDomain)"
}

/** Resumo para listas: sem a senha. */
data class CredentialSummary(
    val id: String,
    val title: String,
    val username: String,
    val websiteDomain: String,
    val categoryId: String?,
    val sensitive: Boolean,
)

data class Category(val id: String, val name: String, val icon: String, val sensitiveByDefault: Boolean)

enum class AuthorizationMode(val wire: String, val label: String) {
    APPROVAL("approval", "Confirmar no celular"),
    AUTOMATIC("automatic", "Automático (dispositivo confiável)"),
    ;

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: APPROVAL
    }
}

enum class DeviceStatus(val wire: String) {
    ACTIVE("active"),
    REVOKED("revoked"),
    ;

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: REVOKED
    }
}

data class TrustedDevice(
    val id: String,
    val deviceName: String,
    val peerPublicKey: String,
    val authorizationMode: AuthorizationMode,
    val status: DeviceStatus,
    val createdAt: Long,
    val lastSeen: Long?,
    val linkId: String,
    val relayUrl: String,
    val peerDeviceId: String,
    val pendingRevocation: Boolean,
    val lastTxSeq: Long = 0L,
    val lastRxSeq: Long = -1L,
)

data class SecurityEvent(
    val id: String,
    val eventType: String,
    val deviceId: String?,
    val credentialId: String?,
    val timestamp: Long,
    val result: String,
)

/** Tipos de evento registrados (nunca com senhas, tokens ou conteúdo de mensagens). */
object EventTypes {
    const val VAULT_CREATED = "vault_created"
    const val VAULT_UNLOCKED = "vault_unlocked"
    const val VAULT_UNLOCK_FAILED = "vault_unlock_failed"
    const val VAULT_LOCKED = "vault_locked"
    const val CREDENTIAL_CREATED = "credential_created"
    const val CREDENTIAL_UPDATED = "credential_updated"
    const val CREDENTIAL_DELETED = "credential_deleted"
    const val CREDENTIAL_SAVED_REMOTE = "credential_saved_from_browser"
    const val PASSWORD_VIEWED = "password_viewed"
    const val DEVICE_PAIRED = "device_paired"
    const val DEVICE_REVOKED = "device_revoked"
    const val DEVICE_MODE_CHANGED = "device_mode_changed"
    const val FILL_APPROVED = "fill_approved"
    const val FILL_AUTOMATIC = "fill_automatic"
    const val FILL_DENIED = "fill_denied"
    const val FILL_BLOCKED_DOMAIN = "fill_blocked_domain"
    const val FILL_LOCKED = "fill_locked"
    const val FILL_DONE = "fill_done"
    const val MESSAGE_REJECTED = "message_rejected"

    fun label(type: String): String = when (type) {
        VAULT_CREATED -> "Cofre criado"
        VAULT_UNLOCKED -> "Cofre desbloqueado"
        VAULT_UNLOCK_FAILED -> "Senha mestra incorreta"
        VAULT_LOCKED -> "Cofre bloqueado"
        CREDENTIAL_CREATED -> "Conta cadastrada"
        CREDENTIAL_UPDATED -> "Conta alterada"
        CREDENTIAL_DELETED -> "Conta excluída"
        CREDENTIAL_SAVED_REMOTE -> "Senha salva pelo computador"
        PASSWORD_VIEWED -> "Senha exibida"
        DEVICE_PAIRED -> "Computador pareado"
        DEVICE_REVOKED -> "Computador desconectado"
        DEVICE_MODE_CHANGED -> "Modo de autorização alterado"
        FILL_APPROVED -> "Preenchimento aprovado"
        FILL_AUTOMATIC -> "Preenchimento automático"
        FILL_DENIED -> "Preenchimento recusado"
        FILL_BLOCKED_DOMAIN -> "Bloqueado: site não confere"
        FILL_LOCKED -> "Pedido com cofre bloqueado"
        FILL_DONE -> "Formulário preenchido no computador"
        MESSAGE_REJECTED -> "Mensagem recusada (integridade ou repetição)"
        else -> type
    }
}
