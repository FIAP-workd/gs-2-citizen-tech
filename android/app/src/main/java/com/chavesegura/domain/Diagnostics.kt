package com.chavesegura.domain

data class DiagnosticInput(val credentialId: String, val title: String, val password: String)

data class DiagnosticReport(
    val weak: List<Pair<String, Strength>>,
    /** Grupos de credenciais que usam a mesma senha (cada grupo tem 2+ ids). */
    val reused: List<List<String>>,
) {
    val problemCount: Int get() = weak.size + reused.sumOf { it.size }
    val isHealthy: Boolean get() = weak.isEmpty() && reused.isEmpty()
}

/**
 * Diagnóstico calculado em memória, depois do desbloqueio (Fase 2).
 * Nenhum resultado é gravado em disco.
 */
object Diagnostics {
    fun analyze(items: List<DiagnosticInput>): DiagnosticReport {
        val weak = items.mapNotNull { item ->
            PasswordStrength.estimate(item.password).takeIf { it.isWeak }?.let { item.credentialId to it }
        }
        val reused = items.groupBy { it.password }
            .values
            .filter { it.size > 1 }
            .map { group -> group.map { it.credentialId } }
        return DiagnosticReport(weak, reused)
    }
}
