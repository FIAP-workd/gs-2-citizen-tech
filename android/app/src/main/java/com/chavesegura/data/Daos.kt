package com.chavesegura.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials")
    suspend fun all(): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun byId(id: String): CredentialEntity?

    /** Comparação exata: o domínio já é gravado normalizado. */
    @Query("SELECT * FROM credentials WHERE website_domain = :domain")
    suspend fun byDomain(domain: String): List<CredentialEntity>

    @Upsert
    suspend fun upsert(entity: CredentialEntity)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<CategoryEntity>)
}

@Dao
interface VaultMetadataDao {
    @Query("SELECT * FROM vault_metadata WHERE id = 1")
    suspend fun get(): VaultMetadataEntity?

    @Upsert
    suspend fun upsert(entity: VaultMetadataEntity)

    @Query("UPDATE vault_metadata SET biometric_vault_key = :blob WHERE id = 1")
    suspend fun setBiometricKey(blob: ByteArray?)
}

@Dao
interface TrustedDeviceDao {
    @Query("SELECT * FROM trusted_devices ORDER BY created_at DESC")
    fun observeAll(): Flow<List<TrustedDeviceEntity>>

    @Query("SELECT * FROM trusted_devices")
    suspend fun all(): List<TrustedDeviceEntity>

    @Query("SELECT * FROM trusted_devices WHERE id = :id")
    suspend fun byId(id: String): TrustedDeviceEntity?

    @Upsert
    suspend fun upsert(entity: TrustedDeviceEntity)

    @Query("UPDATE trusted_devices SET authorization_mode = :mode WHERE id = :id")
    suspend fun setMode(id: String, mode: String)

    @Query("UPDATE trusted_devices SET status = 'revoked', pending_revocation = :pending WHERE id = :id")
    suspend fun revoke(id: String, pending: Boolean)

    @Query("UPDATE trusted_devices SET pending_revocation = 0 WHERE id = :id")
    suspend fun clearPendingRevocation(id: String)

    @Query("UPDATE trusted_devices SET last_seen = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("DELETE FROM trusted_devices WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SecurityEventDao {
    @Query("SELECT * FROM security_events ORDER BY timestamp DESC LIMIT 300")
    fun observeRecent(): Flow<List<SecurityEventEntity>>

    @Insert
    suspend fun insert(event: SecurityEventEntity)
}
