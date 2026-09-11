package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class LivePlanApprovalTest {
    private val plan: LiveAddDeclarationChangePlan =
        LiveAddDeclarationPlanCodec.decode(
                checkNotNull(javaClass.getResource("/live-add-declaration-plan-v1.json")).readText()
            )
            .refined()
    private val pair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val key = BrokerApprovalVerificationKey.decodePinnedX509(pair.public.encoded).refined()
    private val owner = plan.basis.observation.reference.host
    private val challenge = LiveApprovalChallenge.parse("a".repeat(64)).refined()

    @Test
    fun `authentic exact controller assertion retains every subject field`() {
        val proof = verify(signed(payload())).refined()
        assertEquals(plan.planId, proof.planId)
        assertEquals(plan.basis.observation.reference.workspaceRoot, proof.root)
        assertEquals(owner, proof.owner)
        assertEquals(LiveChangeEffect.CHANGE_APPLY, proof.operation)
        assertEquals(challenge, proof.challenge)
        assertEquals("thread-1", proof.invocation.thread)
        assertEquals("turn-1", proof.invocation.turn)
        assertEquals("call-1", proof.invocation.call)
    }

    @Test
    fun `signed but changed subject fails exact binding`() {
        val fields =
            mapOf(
                "root" to ("/other" to LivePlanApprovalFailure.WRONG_ROOT),
                "host" to (UUID(0, 22).toString() to LivePlanApprovalFailure.WRONG_OWNER),
                "planId" to ("b".repeat(64) to LivePlanApprovalFailure.WRONG_PLAN),
                "operation" to ("CHANGE_RECOVER" to LivePlanApprovalFailure.WRONG_OPERATION),
                "challenge" to ("c".repeat(64) to LivePlanApprovalFailure.WRONG_CHALLENGE),
                "keyId" to ("d".repeat(64) to LivePlanApprovalFailure.WRONG_KEY),
                "callId" to ("" to LivePlanApprovalFailure.INVALID_INVOCATION),
            )
        fields.forEach { (name, expected) ->
            assertEquals(
                expected.second,
                assertInstanceOf<Refinement.Rejected<LivePlanApprovalFailure>>(
                        verify(
                            signed(
                                JsonObject(payload().toMutableMap().apply { put(name, JsonPrimitive(expected.first)) })
                            )
                        )
                    )
                    .failure,
                name,
            )
        }
    }

    @Test
    fun `untrusted key and unsigned changes never establish approval`() {
        val foreign = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        assertEquals(
            LivePlanApprovalFailure.SIGNATURE_INVALID,
            assertInstanceOf<Refinement.Rejected<LivePlanApprovalFailure>>(verify(signed(payload(), foreign))).failure,
        )
        val assertion = signed(payload())
        val pieces = assertion.split('.')
        val changed = payload().toString().replace("thread-1", "thread-2")
        assertEquals(
            LivePlanApprovalFailure.SIGNATURE_INVALID,
            assertInstanceOf<Refinement.Rejected<LivePlanApprovalFailure>>(
                    verify("${encode(changed.toByteArray())}.${pieces[1]}")
                )
                .failure,
        )
    }

    @Test
    fun `noncanonical duplicate extended and oversized assertions reject`() {
        val raw = payload().toString()
        val documents =
            listOf(
                "$raw ",
                raw.replace("\"version\":1", "\"version\":1,\"version\":1"),
                raw.replace("\"version\":1", "\"version\":\"1\""),
                raw.dropLast(1) + ",\"approved\":true}",
            )
        documents.forEach { document ->
            assertEquals(
                LivePlanApprovalFailure.ASSERTION_INVALID,
                assertInstanceOf<Refinement.Rejected<LivePlanApprovalFailure>>(verify(signedText(document))).failure,
            )
        }
        assertEquals(
            LivePlanApprovalFailure.ASSERTION_INVALID,
            assertInstanceOf<Refinement.Rejected<LivePlanApprovalFailure>>(verify("a".repeat(16385))).failure,
        )
    }

    @Test
    fun `wrong owner argument and malformed keys fail closed`() {
        assertEquals(
            LivePlanApprovalFailure.WRONG_OWNER,
            assertInstanceOf<Refinement.Rejected<LivePlanApprovalFailure>>(
                    VerifiedLivePlanApproval.verify(
                        signed(payload()),
                        key,
                        LivePlanApprovalExpectation(
                            plan = plan,
                            owner = IdeReadHostLifetime.fromBoundary(UUID(0, 44)),
                            operation = LiveChangeEffect.CHANGE_APPLY,
                            challenge = challenge,
                        ),
                    )
                )
                .failure,
        )
        assertEquals(
            LivePlanApprovalFailure.KEY_INVALID,
            assertInstanceOf<Refinement.Rejected<LivePlanApprovalFailure>>(
                    BrokerApprovalVerificationKey.decodePinnedX509(pair.public.encoded + byteArrayOf(0))
                )
                .failure,
        )
    }

    private fun verify(assertion: String) =
        VerifiedLivePlanApproval.verify(
            assertion,
            key,
            LivePlanApprovalExpectation(
                plan = plan,
                owner = owner,
                operation = LiveChangeEffect.CHANGE_APPLY,
                challenge = challenge,
            ),
        )

    private fun payload(): JsonObject =
        JsonObject(
            linkedMapOf(
                "version" to JsonPrimitive(1),
                "operation" to JsonPrimitive("CHANGE_APPLY"),
                "root" to JsonPrimitive(plan.basis.observation.reference.workspaceRoot.value),
                "host" to JsonPrimitive(owner.value.toString()),
                "planId" to JsonPrimitive(plan.planId.value),
                "challenge" to JsonPrimitive(challenge.value),
                "threadId" to JsonPrimitive("thread-1"),
                "turnId" to JsonPrimitive("turn-1"),
                "callId" to JsonPrimitive("call-1"),
                "keyId" to
                    JsonPrimitive(
                        MessageDigest.getInstance("SHA-256").digest(pair.public.encoded).joinToString("") {
                            "%02x".format(java.util.Locale.ROOT, it)
                        }
                    ),
            )
        )

    private fun signed(document: JsonObject, keyPair: KeyPair = pair) = signedText(document.toString(), keyPair)

    private fun signedText(document: String, keyPair: KeyPair = pair): String {
        val bytes = document.toByteArray()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(bytes)
        return "${encode(bytes)}.${encode(signer.sign())}"
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
