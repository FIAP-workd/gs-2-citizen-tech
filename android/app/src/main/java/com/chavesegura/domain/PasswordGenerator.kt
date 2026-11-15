package com.chavesegura.domain

import java.security.SecureRandom
import kotlin.math.floor
import kotlin.math.ln

data class GeneratorOptions(
    val length: Int = 20,
    val lowercase: Boolean = true,
    val uppercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
)

/**
 * Gerador de senhas fortes com SecureRandom (Fase 2). Mesmo alfabeto da extensão:
 * sem caracteres ambíguos (0/O, 1/l/I), para facilitar ler a senha em voz alta.
 */
class PasswordGenerator(private val random: SecureRandom = SecureRandom()) {

    fun generate(options: GeneratorOptions = GeneratorOptions()): String {
        require(options.length in MIN_LENGTH..MAX_LENGTH) { "O tamanho deve ficar entre $MIN_LENGTH e $MAX_LENGTH." }
        val groups = groupsFor(options)
        require(groups.isNotEmpty()) { "Escolha ao menos um tipo de caractere." }
        val alphabet = groups.joinToString("")
        val chars = groups.map { it[random.nextInt(it.length)] }.toMutableList()
        while (chars.size < options.length) chars += alphabet[random.nextInt(alphabet.length)]
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return chars.joinToString("")
    }

    companion object {
        const val MIN_LENGTH = 8
        const val MAX_LENGTH = 64
        const val LOWER = "abcdefghijkmnopqrstuvwxyz"
        const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val DIGITS = "23456789"
        const val SYMBOLS = "!@#$%&*-_=+?"

        fun groupsFor(options: GeneratorOptions): List<String> = buildList {
            if (options.lowercase) add(LOWER)
            if (options.uppercase) add(UPPER)
            if (options.digits) add(DIGITS)
            if (options.symbols) add(SYMBOLS)
        }

        fun entropyBits(options: GeneratorOptions): Int {
            val size = groupsFor(options).sumOf { it.length }
            return if (size == 0) 0 else floor(options.length * ln(size.toDouble()) / ln(2.0)).toInt()
        }
    }
}
