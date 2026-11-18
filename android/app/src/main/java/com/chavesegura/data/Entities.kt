package com.chavesegura.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Campos sensíveis (`*_encrypted`) guardam `nonce || ciphertext || tag` (AES-256-GCM).
 * O domínio fica em texto para permitir a busca exata (architecture.md §5.1).
 */
@Entity(
    tableName = "credentials",
    indices = [
        Index("website_domain"),
        Index("category_id"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class CredentialEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "username_encrypted", typeAffinity = ColumnInfo.BLOB) val usernameEncrypted: ByteArray,
    @ColumnInfo(name = "password_encrypted", typeAffinity = ColumnInfo.BLOB) val passwordEncrypted: ByteArray,
    @ColumnInfo(name = "website_domain") val websiteDomain: String,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "notes_encrypted", typeAffinity = ColumnInfo.BLOB) val notesEncrypted: ByteArray?,
    val sensitive: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialEntity) return false
        return id == other.id &&
            title == other.title &&
            usernameEncrypted.contentEquals(other.usernameEncrypted) &&
            passwordEncrypted.contentEquals(other.passwordEncrypted) &&
            websiteDomain == other.websiteDomain &&
            categoryId == other.categoryId &&
            ((notesEncrypted == null && other.notesEncrypted == null) ||
                (notesEncrypted != null && other.notesEncrypted != null && notesEncrypted.contentEquals(other.notesEncrypted))) &&
            sensitive == other.sensitive &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + usernameEncrypted.contentHashCode()
        result = 31 * result + passwordEncrypted.contentHashCode()
        result = 31 * result + websiteDomain.hashCode()
        result = 31 * result + (categoryId?.hashCode() ?: 0)
        result = 31 * result + (notesEncrypted?.contentHashCode() ?: 0)
        result = 31 * result + sensitive.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String,
    @ColumnInfo(name = "sensitive_default") val sensitiveDefault: Boolean,
)

/** Linha única (id = 1) com o material para abrir o cofre. */
@Entity(tableName = "vault_metadata")
data class VaultMetadataEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "kdf_version") val kdfVersion: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val salt: ByteArray,
    @ColumnInfo(name = "ops_limit") val opsLimit: Long,
    @ColumnInfo(name = "mem_limit") val memLimit: Long,
    @ColumnInfo(name = "wrapped_vault_key", typeAffinity = ColumnInfo.BLOB) val wrappedVaultKey: ByteArray,
    @ColumnInfo(name = "biometric_vault_key", typeAffinity = ColumnInfo.BLOB) val biometricVaultKey: ByteArray?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultMetadataEntity) return false
        return id == other.id &&
            kdfVersion == other.kdfVersion &&
            salt.contentEquals(other.salt) &&
            opsLimit == other.opsLimit &&
            memLimit == other.memLimit &&
            wrappedVaultKey.contentEquals(other.wrappedVaultKey) &&
            ((biometricVaultKey == null && other.biometricVaultKey == null) ||
                (biometricVaultKey != null && other.biometricVaultKey != null && biometricVaultKey.contentEquals(other.biometricVaultKey))) &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + kdfVersion
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + opsLimit.hashCode()
        result = 31 * result + memLimit.hashCode()
        result = 31 * result + wrappedVaultKey.contentHashCode()
        result = 31 * result + (biometricVaultKey?.contentHashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

/**
 * Computador pareado. A chave privada X25519 do celular e o token do relay
 * ficam cifrados pela chave do Keystore (não dependem da senha mestra, para
 * o app conseguir responder "cofre bloqueado").
 * `last_tx_seq` e `last_rx_seq` preservam o anti-replay entre reinícios.
 */
@Entity(
    tableName = "trusted_devices",
    indices = [
        Index(value = ["link_id"], unique = true),
        Index("status"),
    ],
)
data class TrustedDeviceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "device_name") val deviceName: String,
    @ColumnInfo(name = "public_key") val publicKey: String,
    @ColumnInfo(name = "authorization_mode") val authorizationMode: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_seen") val lastSeen: Long?,
    @ColumnInfo(name = "link_id") val linkId: String,
    @ColumnInfo(name = "relay_url") val relayUrl: String,
    @ColumnInfo(name = "peer_device_id") val peerDeviceId: String,
    @ColumnInfo(name = "own_device_id") val ownDeviceId: String,
    @ColumnInfo(name = "own_token_wrapped", typeAffinity = ColumnInfo.BLOB) val ownTokenWrapped: ByteArray,
    @ColumnInfo(name = "own_public_key") val ownPublicKey: String,
    @ColumnInfo(name = "own_secret_wrapped", typeAffinity = ColumnInfo.BLOB) val ownSecretWrapped: ByteArray,
    @ColumnInfo(name = "pending_revocation") val pendingRevocation: Boolean,
    @ColumnInfo(name = "last_tx_seq", defaultValue = "0") val lastTxSeq: Long = 0L,
    @ColumnInfo(name = "last_rx_seq", defaultValue = "-1") val lastRxSeq: Long = -1L,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedDeviceEntity) return false
        return id == other.id &&
            deviceName == other.deviceName &&
            publicKey == other.publicKey &&
            authorizationMode == other.authorizationMode &&
            status == other.status &&
            createdAt == other.createdAt &&
            lastSeen == other.lastSeen &&
            linkId == other.linkId &&
            relayUrl == other.relayUrl &&
            peerDeviceId == other.peerDeviceId &&
            ownDeviceId == other.ownDeviceId &&
            ownTokenWrapped.contentEquals(other.ownTokenWrapped) &&
            ownPublicKey == other.ownPublicKey &&
            ownSecretWrapped.contentEquals(other.ownSecretWrapped) &&
            pendingRevocation == other.pendingRevocation &&
            lastTxSeq == other.lastTxSeq &&
            lastRxSeq == other.lastRxSeq
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + authorizationMode.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (lastSeen?.hashCode() ?: 0)
        result = 31 * result + linkId.hashCode()
        result = 31 * result + relayUrl.hashCode()
        result = 31 * result + peerDeviceId.hashCode()
        result = 31 * result + ownDeviceId.hashCode()
        result = 31 * result + ownTokenWrapped.contentHashCode()
        result = 31 * result + ownPublicKey.hashCode()
        result = 31 * result + ownSecretWrapped.contentHashCode()
        result = 31 * result + pendingRevocation.hashCode()
        result = 31 * result + lastTxSeq.hashCode()
        result = 31 * result + lastRxSeq.hashCode()
        return result
    }
}

@Entity(
    tableName = "security_events",
    indices = [
        Index("timestamp"),
        Index("device_id"),
        Index("credential_id"),
    ],
)
data class SecurityEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "device_id") val deviceId: String?,
    @ColumnInfo(name = "credential_id") val credentialId: String?,
    val timestamp: Long,
    val result: String,
)
