package com.chavesegura.vault

import com.chavesegura.data.AppDatabase
import com.chavesegura.data.CredentialEntity
import com.chavesegura.data.SecurityLog
import com.chavesegura.data.VaultMetadataEntity
import com.chavesegura.domain.Category
import com.chavesegura.domain.Credential
import com.chavesegura.domain.CredentialSummary
import com.chavesegura.domain.DiagnosticInput
import com.chavesegura.domain.Domains
import com.chavesegura.domain.EventTypes
import com.chavesegura.security.BiometricVaultKey
import com.chavesegura.security.CryptoFailure
import com.chavesegura.security.KdfParams
import com.chavesegura.security.VaultCrypto
import com.chavesegura.security.VaultKeyMaterial
import com.chavesegura.security.WrongPasswordException
import com.chavesegura.security.wipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.crypto.Cipher

sealed interface UnlockResult {
    data object Success : UnlockResult
    data object WrongPassword : UnlockResult
    data class Throttled(val retryAtMillis: Long) : UnlockResult
    /** O banco foi copiado de outro aparelho ou a chave do Keystore sumiu. */
    data object WrongDevice : UnlockResult
}

enum class SaveOutcome { SAVED, UPDATED }

/** Operações sobre o cofre: criação, desbloqueio e credenciais cifradas campo a campo. */
class VaultRepository(
    private val db: AppDatabase,
    private val crypto: VaultCrypto,
    private val session: VaultSession,
    private val settings: AppSettings,
    private val log: SecurityLog,
    private val biometric: BiometricVaultKey,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val credentials = db.credentials()
    private val metadata = db.vaultMetadata()

    suspend fun isCreated(): Boolean = metadata.get() != null

    suspend fun hasBiometric(): Boolean = metadata.get()?.biometricVaultKey != null

    suspend fun create(password: CharArray) = withContext(Dispatchers.Default) {
        check(!isCreated()) { "o cofre já existe" }
        val (material, vaultKey) = crypto.create(password)
        metadata.upsert(
            VaultMetadataEntity(
                kdfVersion = material.params.version,
                salt = material.salt,
                opsLimit = material.params.opsLimit,
                memLimit = material.params.memLimitBytes,
                wrappedVaultKey = material.wrappedVaultKey,
                biometricVaultKey = null,
                createdAt = clock(),
            ),
        )
        if (db.categories().count() == 0) db.categories().insertAll(AppDatabase.DEFAULT_CATEGORIES)
        session.open(vaultKey)
        log.record(EventTypes.VAULT_CREATED, "success")
    }

    suspend fun unlock(password: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        val now = clock()
        if (settings.lockoutUntil > now) return@withContext UnlockResult.Throttled(settings.lockoutUntil)
        val meta = metadata.get() ?: return@withContext UnlockResult.WrongPassword
        val material = VaultKeyMaterial(
            meta.salt,
            KdfParams(meta.kdfVersion, meta.opsLimit, meta.memLimit),
            meta.wrappedVaultKey,
        )
        try {
            session.open(crypto.unlock(password, material))
            settings.failedAttempts = 0
            settings.lockoutUntil = 0
            log.record(EventTypes.VAULT_UNLOCKED, "success")
            UnlockResult.Success
        } catch (e: WrongPasswordException) {
            val failures = settings.failedAttempts + 1
            settings.failedAttempts = failures
            val wait = UnlockThrottle.lockoutMillis(failures)
            if (wait > 0) settings.lockoutUntil = now + wait
            log.record(EventTypes.VAULT_UNLOCK_FAILED, "denied")
            if (wait > 0) UnlockResult.Throttled(now + wait) else UnlockResult.WrongPassword
        } catch (e: CryptoFailure) {
            log.record(EventTypes.VAULT_UNLOCK_FAILED, "wrong_device")
            UnlockResult.WrongDevice
        }
    }

    suspend fun biometricBlob(): ByteArray? = metadata.get()?.biometricVaultKey

    /** Depois do BiometricPrompt: guarda a cópia da VK protegida pela biometria. */
    suspend fun enableBiometric(authenticated: Cipher) {
        val key = session.copyKey()
        try {
            metadata.setBiometricKey(biometric.seal(authenticated, key))
        } finally {
            key.wipe()
        }
    }

    suspend fun disableBiometric() {
        metadata.setBiometricKey(null)
        biometric.delete()
    }

    suspend fun unlockWithBiometric(authenticated: Cipher, blob: ByteArray): UnlockResult {
        return try {
            session.open(biometric.open(authenticated, blob))
            settings.failedAttempts = 0
            log.record(EventTypes.VAULT_UNLOCKED, "biometric")
            UnlockResult.Success
        } catch (e: Exception) {
            log.record(EventTypes.VAULT_UNLOCK_FAILED, "biometric")
            UnlockResult.WrongPassword
        }
    }

    suspend fun lock() {
        if (session.isUnlocked) {
            session.lock()
            log.record(EventTypes.VAULT_LOCKED, "success")
        }
    }

    // ------------------------------------------------------------ leitura ---

    fun observeCategories(): Flow<List<Category>> = db.categories().observeAll().map { rows ->
        rows.map { Category(it.id, it.name, it.icon, it.sensitiveDefault) }
    }

    /** Lista para a tela principal: usuário decifrado, senha não. */
    fun observeSummaries(): Flow<List<CredentialSummary>> =
        combine(credentials.observeAll(), session.unlocked) { rows, unlocked ->
            if (!unlocked) return@combine emptyList()
            val cipher = runCatching { session.cipher() }.getOrNull() ?: return@combine emptyList()
            rows.map { row ->
                CredentialSummary(
                    id = row.id,
                    title = row.title,
                    username = runCatching { cipher.open(row.id, FIELD_USERNAME, row.usernameEncrypted) }
                        .getOrDefault("(não foi possível ler)"),
                    websiteDomain = row.websiteDomain,
                    categoryId = row.categoryId,
                    sensitive = row.sensitive,
                )
            }
        }.flowOn(Dispatchers.Default)

    suspend fun get(id: String): Credential? = withContext(Dispatchers.Default) {
        credentials.byId(id)?.let(::decrypt)
    }

    /** Credenciais cadastradas para exatamente este hostname. */
    suspend fun findByHostname(hostname: String): List<Credential> = withContext(Dispatchers.Default) {
        val normalized = Domains.normalize(hostname) ?: return@withContext emptyList()
        credentials.byDomain(normalized).map(::decrypt)
    }

    /** Domínios salvos que se parecem com o hostname (para alertar sobre imitações). */
    suspend fun lookalikeDomains(hostname: String): List<String> =
        credentials.allDomains().filter { Domains.looksLike(hostname, it) }

    suspend fun diagnosticInputs(): List<DiagnosticInput> = withContext(Dispatchers.Default) {
        credentials.all().map { row ->
            val c = decrypt(row)
            DiagnosticInput(c.id, c.title, c.password)
        }
    }

    private fun decrypt(row: CredentialEntity): Credential {
        val cipher = session.cipher()
        return Credential(
            id = row.id,
            title = row.title,
            username = cipher.open(row.id, FIELD_USERNAME, row.usernameEncrypted),
            password = cipher.open(row.id, FIELD_PASSWORD, row.passwordEncrypted),
            websiteDomain = row.websiteDomain,
            categoryId = row.categoryId,
            notes = row.notesEncrypted?.let { cipher.open(row.id, FIELD_NOTES, it) } ?: "",
            sensitive = row.sensitive,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )
    }

    // ------------------------------------------------------------ escrita ---

    suspend fun save(
        id: String?,
        title: String,
        username: String,
        password: String,
        website: String,
        categoryId: String?,
        notes: String,
        sensitive: Boolean,
    ): String = withContext(Dispatchers.Default) {
        val domain = Domains.normalize(website) ?: throw IllegalArgumentException("Endereço do site inválido.")
        require(title.isNotBlank()) { "Informe o nome do serviço." }
        require(password.isNotEmpty()) { "Informe a senha." }
        val existing = id?.let { credentials.byId(it) }
        val credentialId = existing?.id ?: UUID.randomUUID().toString()
        val validCategoryId = categoryId?.takeIf { db.categories().exists(it) }
        val cipher = session.cipher()
        val now = clock()
        credentials.upsert(
            CredentialEntity(
                id = credentialId,
                title = title.trim(),
                usernameEncrypted = cipher.seal(credentialId, FIELD_USERNAME, username.trim()),
                passwordEncrypted = cipher.seal(credentialId, FIELD_PASSWORD, password),
                websiteDomain = domain,
                categoryId = validCategoryId,
                notesEncrypted = notes.takeIf { it.isNotBlank() }?.let { cipher.seal(credentialId, FIELD_NOTES, it) },
                sensitive = sensitive,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        log.record(
            if (existing == null) EventTypes.CREDENTIAL_CREATED else EventTypes.CREDENTIAL_UPDATED,
            "success",
            credentialId = credentialId,
        )
        credentialId
    }

    /**
     * Senha criada no computador (“Gerar senha forte e salvar”): grava sem
     * pedir confirmação. Se já existe conta com o mesmo site e usuário, atualiza.
     */
    suspend fun saveFromBrowser(hostname: String, username: String, password: String, deviceId: String): SaveOutcome {
        val domain = Domains.normalize(hostname) ?: throw IllegalArgumentException("hostname inválido")
        val match = findByHostname(domain).firstOrNull { it.username.equals(username.trim(), ignoreCase = true) }
        val id = save(
            id = match?.id,
            title = match?.title ?: domain,
            username = username,
            password = password,
            website = domain,
            categoryId = match?.categoryId ?: "outros",
            notes = match?.notes ?: "",
            sensitive = match?.sensitive ?: false,
        )
        log.record(EventTypes.CREDENTIAL_SAVED_REMOTE, if (match == null) "saved" else "updated", deviceId, id)
        return if (match == null) SaveOutcome.SAVED else SaveOutcome.UPDATED
    }

    suspend fun delete(id: String) {
        credentials.delete(id)
        log.record(EventTypes.CREDENTIAL_DELETED, "success", credentialId = id)
    }

    suspend fun recordPasswordViewed(id: String) = log.record(EventTypes.PASSWORD_VIEWED, "success", credentialId = id)

    companion object {
        const val FIELD_USERNAME = "username"
        const val FIELD_PASSWORD = "password"
        const val FIELD_NOTES = "notes"
    }
}
