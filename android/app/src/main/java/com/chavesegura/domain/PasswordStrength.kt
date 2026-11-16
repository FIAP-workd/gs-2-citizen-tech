package com.chavesegura.domain

import kotlin.math.ln

enum class Strength(val label: String) {
    VERY_WEAK("Muito fraca"),
    WEAK("Fraca"),
    FAIR("Razoável"),
    STRONG("Forte"),
    VERY_STRONG("Muito forte"),
    ;

    val isWeak: Boolean get() = this <= WEAK
}

/**
 * Estimativa simples e local de força (nada sai do aparelho). Considera
 * tamanho, variedade de caracteres, senhas muito comuns e sequências.
 */
object PasswordStrength {
    private val COMMON = setOf(
        "123456", "123456789", "12345678", "1234567", "12345", "1234", "111111", "000000",
        "senha", "senha123", "password", "qwerty", "abc123", "102030", "123123", "iloveyou",
        "admin", "brasil", "mudar123", "654321", "abcdef", "teste", "teste123",
    )
    private val SEQUENCES = listOf("0123456789", "abcdefghijklmnopqrstuvwxyz", "qwertyuiop", "asdfghjkl")

    fun estimate(password: String): Strength {
        if (password.isEmpty()) return Strength.VERY_WEAK
        val lower = password.lowercase()
        if (lower in COMMON || lower.trimEnd('!', '.', '1') in COMMON) return Strength.VERY_WEAK
        if (password.toSet().size <= 2) return Strength.VERY_WEAK

        var pool = 0
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { !it.isLetterOrDigit() }) pool += 20
        var bits = password.length * ln(pool.coerceAtLeast(1).toDouble()) / ln(2.0)
        if (SEQUENCES.any { seq -> (0..seq.length - 4).any { lower.contains(seq.substring(it, it + 4)) } }) bits -= 15
        if (Regex("(.)\\1\\1").containsMatchIn(password)) bits -= 10
        if (password.length < 8) bits = bits.coerceAtMost(35.0)

        return when {
            bits < 28 -> Strength.VERY_WEAK
            bits < 45 -> Strength.WEAK
            bits < 60 -> Strength.FAIR
            bits < 80 -> Strength.STRONG
            else -> Strength.VERY_STRONG
        }
    }
}
