package com.chavesegura.authorization

import com.chavesegura.domain.AuthorizationMode
import com.chavesegura.domain.DeviceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthorizationPolicyTest {
    private val now = 1_000_000L
    private val normal = Candidate("c1", sensitive = false)
    private val bank = Candidate("c2", sensitive = true)

    private fun input(
        status: DeviceStatus = DeviceStatus.ACTIVE,
        mode: AuthorizationMode = AuthorizationMode.AUTOMATIC,
        unlocked: Boolean = true,
        trustedUntil: Long? = now + 60_000,
        candidates: List<Candidate> = listOf(normal),
    ) = PolicyInput(status, mode, unlocked, trustedUntil, now, candidates)

    @Test
    fun `modo automatico com confianca valida libera sem perguntar`() {
        assertEquals(Decision.AutoApprove("c1"), AuthorizationPolicy.decide(input()))
    }

    @Test
    fun `modo de confirmacao sempre pergunta`() {
        val d = AuthorizationPolicy.decide(input(mode = AuthorizationMode.APPROVAL))
        assertEquals(Decision.NeedsConfirmation(listOf("c1"), ConfirmReason.APPROVAL_MODE), d)
    }

    @Test
    fun `sessao automatica expirada exige nova autorizacao`() {
        val d = AuthorizationPolicy.decide(input(trustedUntil = now - 1))
        assertEquals(Decision.NeedsConfirmation(listOf("c1"), ConfirmReason.TRUST_EXPIRED), d)
        assertEquals(
            Decision.NeedsConfirmation(listOf("c1"), ConfirmReason.TRUST_EXPIRED),
            AuthorizationPolicy.decide(input(trustedUntil = null)),
        )
    }

    @Test
    fun `credencial sensivel sempre pede confirmacao`() {
        val d = AuthorizationPolicy.decide(input(candidates = listOf(bank)))
        assertEquals(Decision.NeedsConfirmation(listOf("c2"), ConfirmReason.SENSITIVE), d)
    }

    @Test
    fun `varias contas para o mesmo site pedem escolha`() {
        val d = AuthorizationPolicy.decide(input(candidates = listOf(normal, Candidate("c3", false))))
        assertEquals(ConfirmReason.MULTIPLE_ACCOUNTS, (d as Decision.NeedsConfirmation).reason)
    }

    @Test
    fun `dispositivo revogado e recusado`() {
        assertEquals(Decision.DeviceRevoked, AuthorizationPolicy.decide(input(status = DeviceStatus.REVOKED)))
    }

    @Test
    fun `cofre bloqueado nao libera nada`() {
        assertEquals(Decision.Locked, AuthorizationPolicy.decide(input(unlocked = false)))
    }

    @Test
    fun `sem credencial para o dominio exato`() {
        assertEquals(Decision.NotFound, AuthorizationPolicy.decide(input(candidates = emptyList())))
    }
}

class TrustSessionsTest {
    @Test
    fun `confianca expira e pode ser encerrada`() {
        var clock = 0L
        val trust = TrustSessions { clock }
        trust.start("d1", minutes = 15)
        assertEquals(15 * 60_000L, trust.trustedUntil("d1"))
        clock = 15 * 60_000L
        assertNull(trust.trustedUntil("d1"))
        trust.start("d1", minutes = 15)
        trust.clear()
        assertNull(trust.trustedUntil("d1"))
    }
}
