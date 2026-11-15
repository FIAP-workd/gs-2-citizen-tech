package com.chavesegura.authorization

import com.chavesegura.domain.AuthorizationMode
import com.chavesegura.domain.DeviceStatus

/** Por que o pedido precisa de confirmação manual (mostrado ao usuário). */
enum class ConfirmReason(val text: String) {
    APPROVAL_MODE("Este computador está no modo de confirmação."),
    TRUST_EXPIRED("A confiança automática expirou."),
    SENSITIVE("Conta sensível: sempre pede confirmação."),
    MULTIPLE_ACCOUNTS("Há mais de uma conta para este site. Escolha qual usar."),
}

sealed interface Decision {
    data object DeviceRevoked : Decision
    data object Locked : Decision
    data object NotFound : Decision
    data class AutoApprove(val credentialId: String) : Decision
    data class NeedsConfirmation(val candidateIds: List<String>, val reason: ConfirmReason) : Decision
}

data class Candidate(val id: String, val sensitive: Boolean)

data class PolicyInput(
    val deviceStatus: DeviceStatus,
    val mode: AuthorizationMode,
    val vaultUnlocked: Boolean,
    /** Fim da sessão de confiança do dispositivo, ou null se não houver. */
    val trustedUntil: Long?,
    val now: Long,
    /** Credenciais com domínio EXATAMENTE igual ao hostname pedido. */
    val candidates: List<Candidate>,
)

/**
 * Regras do modo automático (architecture.md §7.4, threat-model A1–A3).
 * Falha fechada: qualquer condição não satisfeita leva à confirmação ou à recusa.
 */
object AuthorizationPolicy {
    fun decide(input: PolicyInput): Decision {
        if (input.deviceStatus != DeviceStatus.ACTIVE) return Decision.DeviceRevoked
        if (!input.vaultUnlocked) return Decision.Locked
        if (input.candidates.isEmpty()) return Decision.NotFound

        val ids = input.candidates.map { it.id }
        if (input.mode != AuthorizationMode.AUTOMATIC) {
            return Decision.NeedsConfirmation(ids, ConfirmReason.APPROVAL_MODE)
        }
        val trusted = input.trustedUntil?.let { input.now < it } ?: false
        if (!trusted) return Decision.NeedsConfirmation(ids, ConfirmReason.TRUST_EXPIRED)
        if (input.candidates.size > 1) return Decision.NeedsConfirmation(ids, ConfirmReason.MULTIPLE_ACCOUNTS)
        val only = input.candidates.single()
        if (only.sensitive) return Decision.NeedsConfirmation(ids, ConfirmReason.SENSITIVE)
        return Decision.AutoApprove(only.id)
    }
}

/**
 * Sessões de confiança do modo automático: começam quando o cofre é aberto
 * (ou quando o usuário liga/renova o modo), duram o tempo configurado e
 * terminam quando o cofre é bloqueado.
 */
class TrustSessions(private val clock: () -> Long = System::currentTimeMillis) {
    private val until = mutableMapOf<String, Long>()

    @Synchronized
    fun start(deviceId: String, minutes: Int) {
        until[deviceId] = clock() + minutes * 60_000L
    }

    @Synchronized
    fun end(deviceId: String) {
        until.remove(deviceId)
    }

    @Synchronized
    fun clear() = until.clear()

    @Synchronized
    fun trustedUntil(deviceId: String): Long? = until[deviceId]?.takeIf { it > clock() }

    @Synchronized
    fun snapshot(): Map<String, Long> = until.filterValues { it > clock() }
}
