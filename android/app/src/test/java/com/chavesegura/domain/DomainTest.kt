package com.chavesegura.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainsTest {
    @Test
    fun `normaliza endereco completo`() {
        assertEquals("accounts.exemplo.com", Domains.normalize("HTTPS://Accounts.Exemplo.com.:443/login?x=1"))
        assertEquals("exemplo.com", Domains.normalize("exemplo.com"))
        assertEquals("localhost", Domains.normalize("http://localhost:8080/login.html"))
    }

    @Test
    fun `converte dominio com acento para punycode igual a extensao`() {
        assertEquals("xn--bnco-5na.com", Domains.normalize("https://bánco.com"))
    }

    @Test
    fun `recusa entradas invalidas`() {
        assertNull(Domains.normalize(""))
        assertNull(Domains.normalize("a..b"))
    }

    @Test
    fun `dominio semelhante ao original nao confere`() {
        assertTrue(Domains.matches("gmail.com", "https://gmail.com/"))
        listOf("gmail.com.site-falso.com", "login-gmail.com", "gmail.co", "mail.gmail.com").forEach {
            assertFalse(it, Domains.matches(it, "gmail.com"))
        }
        assertTrue(Domains.looksLike("gmail.com.site-falso.com", "gmail.com"))
        assertFalse(Domains.looksLike("gmail.com", "gmail.com"))
    }
}

class PasswordGeneratorTest {
    private val generator = PasswordGenerator()

    @Test
    fun `respeita tamanho e inclui todos os tipos`() {
        repeat(200) {
            val p = generator.generate(GeneratorOptions(length = 12))
            assertEquals(12, p.length)
            assertTrue(p.any { it in PasswordGenerator.LOWER })
            assertTrue(p.any { it in PasswordGenerator.UPPER })
            assertTrue(p.any { it in PasswordGenerator.DIGITS })
            assertTrue(p.any { it in PasswordGenerator.SYMBOLS })
        }
    }

    @Test
    fun `nao repete senhas`() {
        assertEquals(500, List(500) { generator.generate() }.toSet().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recusa tamanho curto`() {
        generator.generate(GeneratorOptions(length = 4))
    }

    @Test
    fun `senha gerada e classificada como forte`() {
        assertFalse(PasswordStrength.estimate(generator.generate()).isWeak)
        assertTrue(PasswordGenerator.entropyBits(GeneratorOptions()) > 100)
    }
}

class PasswordStrengthTest {
    @Test
    fun `senhas comuns e curtas sao fracas`() {
        listOf("123456", "senha123", "password", "aaaaaaaa", "abc123", "qwerty", "Ana1").forEach {
            assertTrue(it, PasswordStrength.estimate(it).isWeak)
        }
    }

    @Test
    fun `frases longas sao fortes`() {
        assertFalse(PasswordStrength.estimate("cavalo-bateria-grampo-correto-9").isWeak)
    }
}

class DiagnosticsTest {
    @Test
    fun `identifica senhas fracas e repetidas`() {
        val report = Diagnostics.analyze(
            listOf(
                DiagnosticInput("1", "Banco", "Tr0ca-Esta-Senha-Ja!"),
                DiagnosticInput("2", "E-mail", "Tr0ca-Esta-Senha-Ja!"),
                DiagnosticInput("3", "Loja", "123456"),
                DiagnosticInput("4", "Rede", "x9!Lq#2vR7mZ@4pW"),
            ),
        )
        assertEquals(listOf("3"), report.weak.map { it.first })
        assertEquals(listOf(listOf("1", "2")), report.reused)
        assertFalse(report.isHealthy)
    }
}
