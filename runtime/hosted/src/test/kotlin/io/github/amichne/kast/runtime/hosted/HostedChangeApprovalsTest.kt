package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.change.apply.BrokerApprovalVerificationKey
import io.github.amichne.kast.change.apply.LiveApprovalChallenge
import io.github.amichne.kast.change.apply.LiveChangeEffect
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostedChangeApprovalsTest {
    private val fixture = HostedApprovalFixture()
    private val apply = LiveChangeEffect.CHANGE_APPLY

    @Test
    fun `actual owner challenge admits signed exact assertion once`() {
        val approvals = HostedChangeApprovals(fixture.owner) { Refinement.Refined(fixture.key) }
        val challenge = approvals.prepare(fixture.plan, apply).approvalRefined()
        val signed = fixture.signed(challenge)
        val proof = approvals.consume(fixture.plan, apply, signed).approvalRefined()
        assertEquals(fixture.owner, proof.owner)
        assertEquals(fixture.plan.planId, proof.planId)
        assertEquals(challenge, proof.challenge)
        assertEquals("call-1", proof.invocation.call)
        assertEquals(
            Refinement.Rejected(HostedApprovalFailure.NOT_PENDING),
            approvals.consume(fixture.plan, apply, signed),
        )
    }

    @Test
    fun `malformed and wrong signed subject consume their pending challenge once`() {
        listOf<(LiveApprovalChallenge) -> String>(
                { "approved" },
                { fixture.signed(it, LiveChangeEffect.CHANGE_RECOVER) },
                { fixture.signed(it, changes = mapOf("planId" to JsonPrimitive("c".repeat(64)))) },
                {
                    fixture.signed(it, changes = mapOf("host" to JsonPrimitive("00000000-0000-0000-0000-000000000002")))
                },
            )
            .forEach { assertion ->
                val approvals = HostedChangeApprovals(fixture.owner) { Refinement.Refined(fixture.key) }
                val challenge = approvals.prepare(fixture.plan, apply).approvalRefined()
                assertEquals(
                    Refinement.Rejected(HostedApprovalFailure.INVALID_ASSERTION),
                    approvals.consume(fixture.plan, apply, assertion(challenge)),
                )
                assertEquals(
                    Refinement.Rejected(HostedApprovalFailure.NOT_PENDING),
                    approvals.consume(fixture.plan, apply, fixture.signed(challenge)),
                )
            }
    }

    @Test
    fun `effects retain separate challenges and retired owner cannot prepare or consume`() {
        val approvals = HostedChangeApprovals(fixture.owner) { Refinement.Refined(fixture.key) }
        val first = approvals.prepare(fixture.plan, apply).approvalRefined()
        val recovery = approvals.prepare(fixture.plan, LiveChangeEffect.CHANGE_RECOVER).approvalRefined()
        assertNotEquals(first, recovery)
        assertEquals(
            Refinement.Rejected(HostedApprovalFailure.INVALID_ASSERTION),
            approvals.consume(fixture.plan, LiveChangeEffect.CHANGE_RECOVER, fixture.signed(first)),
        )
        approvals.consume(fixture.plan, apply, fixture.signed(first)).approvalRefined()
        approvals.close()
        assertEquals(Refinement.Rejected(HostedApprovalFailure.RETIRED), approvals.prepare(fixture.plan, apply))
        assertEquals(
            Refinement.Rejected(HostedApprovalFailure.RETIRED),
            approvals.consume(fixture.plan, apply, fixture.signed(first)),
        )
    }

    @Test
    fun `key replacement cannot authorize an already issued challenge`() {
        var key = fixture.key
        val approvals = HostedChangeApprovals(fixture.owner) { Refinement.Refined(key) }
        val challenge = approvals.prepare(fixture.plan, apply).approvalRefined()
        val replacement = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        key = BrokerApprovalVerificationKey.decodePinnedX509(replacement.public.encoded).approvalRefined()
        assertEquals(
            Refinement.Rejected(HostedApprovalFailure.KEY_CHANGED),
            approvals.consume(fixture.plan, apply, fixture.signed(challenge, signer = replacement)),
        )
        assertEquals(
            Refinement.Rejected(HostedApprovalFailure.NOT_PENDING),
            approvals.consume(fixture.plan, apply, fixture.signed(challenge)),
        )
        val fresh = approvals.prepare(fixture.plan, apply).approvalRefined()
        approvals.consume(fixture.plan, apply, fixture.signed(fresh, signer = replacement)).approvalRefined()
    }

    @Test
    fun `missing key fails closed without creating enrollment files`(@TempDir home: Path) {
        val approvals = HostedChangeApprovals(fixture.owner) { loadHostedApprovalKey(home) }
        assertEquals(Refinement.Rejected(HostedApprovalFailure.UNAVAILABLE), approvals.prepare(fixture.plan, apply))
        assertFalse(Files.exists(home.resolve(".kast")))
    }

    @Test
    fun `key loader accepts only bounded enrolled regular files`(@TempDir home: Path) {
        val directory = Files.createDirectories(home.resolve(".kast/approval"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val key = directory.resolve("broker.pub")
        Files.write(key, fixture.pair.public.encoded)
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"))
        loadHostedApprovalKey(home).approvalRefined()
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"))
        assertEquals(Refinement.Rejected(HostedApprovalFailure.UNAVAILABLE), loadHostedApprovalKey(home))
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"))
        Files.write(key, ByteArray(129))
        assertEquals(Refinement.Rejected(HostedApprovalFailure.UNAVAILABLE), loadHostedApprovalKey(home))
        Files.delete(key)
        Files.createSymbolicLink(key, home.resolve("target"))
        assertEquals(Refinement.Rejected(HostedApprovalFailure.UNAVAILABLE), loadHostedApprovalKey(home))
    }
}
