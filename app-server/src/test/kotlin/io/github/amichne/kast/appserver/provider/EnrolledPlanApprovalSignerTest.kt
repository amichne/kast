package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.runtime.ClientConnectionId
import io.github.amichne.kast.appserver.runtime.ControllerApprovedPlan
import io.github.amichne.kast.appserver.runtime.ExactPlanApprovalOutcome
import io.github.amichne.kast.appserver.runtime.ExactPlanApprovalResolution
import io.github.amichne.kast.appserver.runtime.ExactPlanApprovalSubject
import io.github.amichne.kast.appserver.runtime.HostedApprovalOwnerId
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalFailure
import io.github.amichne.kast.appserver.runtime.PendingExactPlanApproval
import io.github.amichne.kast.appserver.runtime.SharedTaskSessions
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EnrolledPlanApprovalSignerTest {
    @Test
    fun `enrolled signer authenticates exact plan owner challenge and controller invocation`(@TempDir home: Path) {
        val keys = enroll(home)
        val proof = approval(home)
        val grant = (EnrolledPlanApprovalSigner(home).sign(proof) as Refinement.Refined).value
        assertSame(proof, grant.approval)
        val (payloadPart, signaturePart) = grant.assertion.split('.')
        val payload = Base64.getUrlDecoder().decode(payloadPart)
        assertTrue(
            Signature.getInstance("Ed25519").run {
                initVerify(keys.public)
                update(payload)
                verify(Base64.getUrlDecoder().decode(signaturePart))
            }
        )
        val expected = buildJsonObject {
            put("version", 1)
            put("operation", "CHANGE_APPLY")
            put("root", home.toRealPath().toString())
            put("host", "11111111-1111-1111-1111-111111111111")
            put("planId", "a".repeat(64))
            put("challenge", "b".repeat(64))
            put("threadId", "thread-1")
            put("turnId", "turn-1")
            put("callId", "call-1")
            put(
                "keyId",
                java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keys.public.encoded)),
            )
        }
        assertArrayEquals(expected.toString().toByteArray(Charsets.UTF_8), payload)
        assertEquals(
            grant.assertion,
            (EnrolledPlanApprovalSigner(home).sign(proof) as Refinement.Refined).value.assertion,
        )
    }

    @Test
    fun `missing enrollment never creates authority`(@TempDir home: Path) {
        assertEquals(
            Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_UNAVAILABLE),
            EnrolledPlanApprovalSigner(home).sign(approval(home)),
        )
        assertFalse(Files.exists(home.resolve(".kast")))
    }

    @Test
    fun `insecure corrupt symlinked and mismatched keys cannot sign`(@TempDir home: Path) {
        enroll(home)
        val signer = EnrolledPlanApprovalSigner(home)
        val proof = approval(home)
        val privatePath = home.resolve(".kast/approval/broker.pk8")
        val publicPath = home.resolve(".kast/approval/broker.pub")
        Files.setPosixFilePermissions(privatePath, PosixFilePermissions.fromString("rw-r--r--"))
        assertEquals(Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED), signer.sign(proof))
        Files.setPosixFilePermissions(privatePath, PosixFilePermissions.fromString("rw-------"))
        Files.write(publicPath, KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)
        assertEquals(Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED), signer.sign(proof))
        Files.write(privatePath, byteArrayOf(1, 2, 3))
        assertEquals(Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED), signer.sign(proof))
        val target = home.resolve("private-key")
        Files.move(privatePath, target)
        Files.createSymbolicLink(privatePath, target)
        assertEquals(Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_REJECTED), signer.sign(proof))
    }

    private fun enroll(home: Path): KeyPair {
        val directory = Files.createDirectories(home.resolve(".kast/approval"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        listOf("broker.pk8" to pair.private.encoded, "broker.pub" to pair.public.encoded).forEach { (name, bytes) ->
            Files.write(directory.resolve(name), bytes)
            Files.setPosixFilePermissions(directory.resolve(name), PosixFilePermissions.fromString("rw-------"))
        }
        return pair
    }

    private fun approval(home: Path): ControllerApprovedPlan {
        val invocation =
            (BrokerInvocationContext.admit(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    callId = "call-1",
                    workingDirectory = home.toRealPath(),
                ) as Refinement.Refined)
                .value
        val subject =
            (ExactPlanApprovalSubject.admit(
                    planIdentity = "a".repeat(64),
                    hostedChallenge = "b".repeat(64),
                    root = invocation.workingDirectory,
                    host =
                        (HostedApprovalOwnerId.admit("11111111-1111-1111-1111-111111111111") as Refinement.Refined)
                            .value,
                ) as Refinement.Refined)
                .value
        val tasks = SharedTaskSessions()
        val controller = ClientConnectionId.fresh()
        tasks.connect(controller)
        tasks.attach(invocation.threadId, controller)
        val pending = (PendingExactPlanApproval.open(subject, invocation, tasks) as Refinement.Refined).value
        val resolved =
            pending.respond(controller, buildJsonObject { put("decision", "accept") })
                as ExactPlanApprovalResolution.Resolved
        return (resolved.outcome as ExactPlanApprovalOutcome.Approved).proof
    }
}
