package com.chavesegura.vault

import com.chavesegura.security.FieldCipher
import com.chavesegura.security.wipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Guarda a vault key em memória enquanto o cofre está aberto. Bloquear apaga a
 * chave. A sessão expira por inatividade (threat-model C6).
 */
class VaultSession(private val clock: () -> Long = System::currentTimeMillis) {
    private var vaultKey: ByteArray? = null
    private var lastActivity = 0L
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Momento do último desbloqueio (as sessões de confiança começam aqui). */
    var unlockedAt: Long = 0L
        private set

    val isUnlocked: Boolean get() = vaultKey != null

    @Synchronized
    fun open(key: ByteArray) {
        vaultKey?.wipe()
        vaultKey = key
        unlockedAt = clock()
        lastActivity = unlockedAt
        _unlocked.value = true
    }

    @Synchronized
    fun lock() {
        vaultKey?.wipe()
        vaultKey = null
        _unlocked.value = false
    }

    fun touch() {
        lastActivity = clock()
    }

    /** Bloqueia se passou do tempo de inatividade. Devolve true se bloqueou agora. */
    @Synchronized
    fun lockIfIdle(timeoutMillis: Long): Boolean {
        if (vaultKey == null || clock() - lastActivity < timeoutMillis) return false
        lock()
        return true
    }

    @Synchronized
    fun cipher(): FieldCipher {
        val key = vaultKey ?: throw VaultLockedException()
        return FieldCipher(key.copyOf())
    }

    @Synchronized
    fun copyKey(): ByteArray = vaultKey?.copyOf() ?: throw VaultLockedException()
}

class VaultLockedException : Exception("cofre bloqueado")
