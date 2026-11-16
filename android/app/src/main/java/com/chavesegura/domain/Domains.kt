package com.chavesegura.domain

import java.net.IDN
import java.net.URI

/**
 * Mesmas regras da extensão (extension/src/domain): o hostname é comparado de
 * forma EXATA depois de normalizado. Nunca por substring, prefixo ou sufixo.
 */
object Domains {
    fun normalize(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(raw)) raw else "https://$raw"
        val host = try {
            URI(withScheme).host ?: hostFallback(withScheme)
        } catch (e: Exception) {
            hostFallback(withScheme)
        } ?: return null
        var result = try {
            IDN.toASCII(host.trim(), IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (e: IllegalArgumentException) {
            return null
        }
        while (result.endsWith(".")) result = result.dropLast(1)
        if (result.isEmpty() || result.contains("..") || result.any { it.isWhitespace() || it == '/' }) return null
        return result
    }

    /** `URI` recusa hosts com acentos; extrai o host manualmente nesses casos. */
    private fun hostFallback(url: String): String? {
        val afterScheme = url.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfter('@').let {
            if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':')
        }
        return host.ifBlank { null }
    }

    fun matches(pageHostname: String, savedDomain: String): Boolean {
        val a = normalize(pageHostname)
        val b = normalize(savedDomain)
        return a != null && a == b
    }

    /** `gmail.com.site-falso.com` imita `gmail.com`: usado para alertar e registrar. */
    fun looksLike(pageHostname: String, savedDomain: String): Boolean {
        val host = normalize(pageHostname) ?: return false
        val known = normalize(savedDomain) ?: return false
        if (host == known) return false
        return host.contains(known) || host.contains(known.substringBefore('.'))
    }
}
