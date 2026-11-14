package com.chavesegura

import org.junit.Assert.assertEquals
import org.junit.Test

/** Garante que a infraestrutura de testes unitários está funcionando no CI. */
class SmokeTest {
    @Test
    fun `infraestrutura de testes executa`() {
        assertEquals(4, 2 + 2)
    }
}
