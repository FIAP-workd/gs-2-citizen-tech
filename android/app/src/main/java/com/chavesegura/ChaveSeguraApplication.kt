package com.chavesegura

import android.app.Application
import android.content.Context
import com.chavesegura.authorization.RequestCoordinator
import com.chavesegura.authorization.TrustSessions
import com.chavesegura.communication.RelayClient
import com.chavesegura.communication.SodiumE2eCrypto
import com.chavesegura.data.AppDatabase
import com.chavesegura.data.SecurityLog
import com.chavesegura.domain.EventTypes
import com.chavesegura.domain.PasswordGenerator
import com.chavesegura.pairing.DeviceRepository
import com.chavesegura.pairing.PairingClient
import com.chavesegura.security.Argon2idKdf
import com.chavesegura.security.BiometricVaultKey
import com.chavesegura.security.KeystoreWrapper
import com.chavesegura.security.VaultCrypto
import com.chavesegura.vault.AppSettings
import com.chavesegura.vault.VaultRepository
import com.chavesegura.vault.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChaveSeguraApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Injeção de dependências manual (suficiente para o MVP). */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(context)
    val settings = AppSettings(context)
    val log = SecurityLog(db.securityEvents())
    val session = VaultSession()
    val biometric = BiometricVaultKey()
    private val deviceWrapper = KeystoreWrapper()
    val vault = VaultRepository(db, VaultCrypto(Argon2idKdf(), deviceWrapper), session, settings, log, biometric)
    val devices = DeviceRepository(db.trustedDevices(), deviceWrapper, log)
    val generator = PasswordGenerator()
    private val http = RelayClient.httpClient()
    val pairing = PairingClient(http, SodiumE2eCrypto)
    val coordinator = RequestCoordinator(
        scope, devices, vault, session, TrustSessions(), settings, log, SodiumE2eCrypto, http,
    )

    init {
        // Bloqueio automático por inatividade (threat-model C6).
        scope.launch {
            while (true) {
                delay(10_000)
                if (session.lockIfIdle(settings.autoLockMinutes * 60_000L)) {
                    log.record(EventTypes.VAULT_LOCKED, "idle")
                }
            }
        }
    }
}
