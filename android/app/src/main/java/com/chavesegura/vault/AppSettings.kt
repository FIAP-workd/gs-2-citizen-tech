package com.chavesegura.vault

import android.content.Context

/** Preferências não secretas do app. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("chave_segura_settings", Context.MODE_PRIVATE)

    var autoLockMinutes: Int
        get() = prefs.getInt("auto_lock_minutes", 5)
        set(value) = prefs.edit().putInt("auto_lock_minutes", value).apply()

    /** Duração da sessão de confiança do modo automático. */
    var trustMinutes: Int
        get() = prefs.getInt("trust_minutes", 60)
        set(value) = prefs.edit().putInt("trust_minutes", value).apply()

    var failedAttempts: Int
        get() = prefs.getInt("failed_attempts", 0)
        set(value) = prefs.edit().putInt("failed_attempts", value).apply()

    var lockoutUntil: Long
        get() = prefs.getLong("lockout_until", 0)
        set(value) = prefs.edit().putLong("lockout_until", value).apply()

    var deviceName: String
        get() = prefs.getString("device_name", null) ?: "Meu celular"
        set(value) = prefs.edit().putString("device_name", value).apply()

    companion object {
        val AUTO_LOCK_OPTIONS = listOf(1, 5, 15, 30)
        val TRUST_OPTIONS = listOf(1, 15, 60, 240, 480)
    }
}

/** Atraso crescente após erros seguidos de senha mestra. */
object UnlockThrottle {
    const val FREE_ATTEMPTS = 5

    fun lockoutMillis(failedAttempts: Int): Long =
        if (failedAttempts < FREE_ATTEMPTS) 0 else 30_000L * (1L shl (failedAttempts - FREE_ATTEMPTS).coerceAtMost(5))
}
