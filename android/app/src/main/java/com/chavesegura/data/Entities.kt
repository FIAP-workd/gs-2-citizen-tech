package com.chavesegura.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Campos sensíveis (`*_encrypted`) guardam `nonce || ciphertext || tag` (AES-256-GCM).
 * O domínio fica em texto para permitir a busca exata (architecture.md §5.1).
 */
@Entity(tableName = "credentials", indices = [Index("website_domain")])
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
)

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
)

/**
 * Computador pareado. A chave privada X25519 do celular e o token do relay
 * ficam cifrados pela chave do Keystore (não dependem da senha mestra, para
 * o app conseguir responder "cofre bloqueado").
 */
@Entity(tableName = "trusted_devices")
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
)

@Entity(tableName = "security_events", indices = [Index("timestamp")])
data class SecurityEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "device_id") val deviceId: String?,
    @ColumnInfo(name = "credential_id") val credentialId: String?,
    val timestamp: Long,
    val result: String,
)
