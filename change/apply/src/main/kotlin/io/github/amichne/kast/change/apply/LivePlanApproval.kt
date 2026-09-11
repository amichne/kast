package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.contract.ChangePlanId
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

enum class LiveChangeEffect {
    CHANGE_APPLY,
    CHANGE_RECOVER,
}

enum class LivePlanApprovalFailure {
    KEY_INVALID,
    ASSERTION_INVALID,
    SIGNATURE_INVALID,
    WRONG_KEY,
    WRONG_PLAN,
    WRONG_ROOT,
    WRONG_OWNER,
    WRONG_OPERATION,
    WRONG_CHALLENGE,
    INVALID_INVOCATION,
}

/** A representation-valid nonce. Only the project owner knows whether it has been issued and remains unspent. */
@JvmInline
value class LiveApprovalChallenge private constructor(val value: String) {
    companion object {
        fun parse(value: String): Refinement<LiveApprovalChallenge, LivePlanApprovalFailure> =
            if (value.matches(Regex("[0-9a-f]{64}"))) Refinement.Refined(LiveApprovalChallenge(value))
            else Refinement.Rejected(LivePlanApprovalFailure.WRONG_CHALLENGE)
    }
}

/** Verification material supplied exclusively by the owner's pinned installation configuration. */
class BrokerApprovalVerificationKey private constructor(internal val key: PublicKey, internal val identity: String) {
    fun sameAuthority(other: BrokerApprovalVerificationKey): Boolean = key.encoded.contentEquals(other.key.encoded)

    companion object {
        fun decodePinnedX509(encoded: ByteArray): Refinement<BrokerApprovalVerificationKey, LivePlanApprovalFailure> {
            if (encoded.size > MAX_APPROVAL_KEY_BYTES) return Refinement.Rejected(LivePlanApprovalFailure.KEY_INVALID)
            return try {
                val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encoded))
                if (!key.encoded.contentEquals(encoded)) return Refinement.Rejected(LivePlanApprovalFailure.KEY_INVALID)
                val identity =
                    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") {
                        "%02x".format(java.util.Locale.ROOT, it)
                    }
                Refinement.Refined(BrokerApprovalVerificationKey(key, identity))
            } catch (_: java.security.GeneralSecurityException) {
                Refinement.Rejected(LivePlanApprovalFailure.KEY_INVALID)
            }
        }
    }
}

/** Exact controller invocation retained as historical approval evidence, without a user-controlled approval flag. */
class LiveApprovedInvocation private constructor(val thread: String, val turn: String, val call: String) {
    internal companion object {
        fun admit(
            thread: String,
            turn: String,
            call: String,
        ): Refinement<LiveApprovedInvocation, LivePlanApprovalFailure> =
            if (
                listOf(thread, turn, call).any {
                    it.isEmpty() || it.length > MAX_INVOCATION_ID_LENGTH || it.any(Char::isISOControl)
                }
            )
                Refinement.Rejected(LivePlanApprovalFailure.INVALID_INVOCATION)
            else Refinement.Refined(LiveApprovedInvocation(thread, turn, call))
    }
}

data class LivePlanApprovalExpectation(
    val plan: LiveAddDeclarationChangePlan,
    val owner: IdeReadHostLifetime,
    val operation: LiveChangeEffect,
    val challenge: LiveApprovalChallenge,
)

private const val MAX_APPROVAL_KEY_BYTES = 128
private const val MAX_INVOCATION_ID_LENGTH = 4096
private const val MAX_ASSERTION_LENGTH = 16_384
private const val ED25519_SIGNATURE_BYTES = 64

/** Cryptographic proof only. Fresh owner admission and durable recovery are still required before mutation. */
class VerifiedLivePlanApproval
private constructor(
    val planId: ChangePlanId,
    val root: CanonicalWorkspaceRoot,
    val owner: IdeReadHostLifetime,
    val operation: LiveChangeEffect,
    val challenge: LiveApprovalChallenge,
    val invocation: LiveApprovedInvocation,
) {
    companion object {
        fun verify(
            assertion: String,
            key: BrokerApprovalVerificationKey,
            expected: LivePlanApprovalExpectation,
        ): Refinement<VerifiedLivePlanApproval, LivePlanApprovalFailure> {
            val plan = expected.plan
            val owner = expected.owner
            val operation = expected.operation
            val challenge = expected.challenge
            val document =
                when (val result = verifyDocument(assertion, key)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            fun text(name: String): String = (document.getValue(name) as JsonPrimitive).content
            val root = plan.basis.observation.reference.workspaceRoot
            when {
                text("keyId") != key.identity -> return rejected(LivePlanApprovalFailure.WRONG_KEY)
                text("operation") != operation.name -> return rejected(LivePlanApprovalFailure.WRONG_OPERATION)
                text("planId") != plan.planId.value -> return rejected(LivePlanApprovalFailure.WRONG_PLAN)
                text("root") != root.value -> return rejected(LivePlanApprovalFailure.WRONG_ROOT)
                text("host") != owner.value.toString() -> return rejected(LivePlanApprovalFailure.WRONG_OWNER)
                text("challenge") != challenge.value -> return rejected(LivePlanApprovalFailure.WRONG_CHALLENGE)
            }
            val invocation =
                when (val result = LiveApprovedInvocation.admit(text("threadId"), text("turnId"), text("callId"))) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            return Refinement.Refined(
                VerifiedLivePlanApproval(
                    planId = plan.planId,
                    root = root,
                    owner = owner,
                    operation = operation,
                    challenge = challenge,
                    invocation = invocation,
                )
            )
        }

        private fun verifyDocument(
            assertion: String,
            key: BrokerApprovalVerificationKey,
        ): Refinement<JsonObject, LivePlanApprovalFailure> {
            if (
                assertion.length > MAX_ASSERTION_LENGTH || !assertion.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
            ) {
                return rejected(LivePlanApprovalFailure.ASSERTION_INVALID)
            }
            return try {
                val payload =
                    when (val verified = verifyPayload(assertion, key)) {
                        is Refinement.Refined -> verified.value
                        is Refinement.Rejected -> return verified
                    }
                val text =
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(payload))
                        .toString()
                val document =
                    Json.parseToJsonElement(text) as? JsonObject
                        ?: return rejected(LivePlanApprovalFailure.ASSERTION_INVALID)
                if (document.keys.toList() != FIELDS || document.toString() != text) {
                    return rejected(LivePlanApprovalFailure.ASSERTION_INVALID)
                }
                if (!validFields(document)) return rejected(LivePlanApprovalFailure.ASSERTION_INVALID)
                Refinement.Refined(document)
            } catch (_: IllegalArgumentException) {
                rejected(LivePlanApprovalFailure.ASSERTION_INVALID)
            } catch (_: java.nio.charset.CharacterCodingException) {
                rejected(LivePlanApprovalFailure.ASSERTION_INVALID)
            } catch (_: java.security.GeneralSecurityException) {
                rejected(LivePlanApprovalFailure.SIGNATURE_INVALID)
            }
        }

        private fun verifyPayload(
            assertion: String,
            key: BrokerApprovalVerificationKey,
        ): Refinement<ByteArray, LivePlanApprovalFailure> {
            val parts = assertion.split('.')
            val decoder = Base64.getUrlDecoder()
            val payload = decoder.decode(parts[0])
            val signature = decoder.decode(parts[1])
            val encoder = Base64.getUrlEncoder().withoutPadding()
            if (
                signature.size != ED25519_SIGNATURE_BYTES ||
                    encoder.encodeToString(payload) != parts[0] ||
                    encoder.encodeToString(signature) != parts[1]
            ) {
                return rejected(LivePlanApprovalFailure.ASSERTION_INVALID)
            }
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key.key)
            verifier.update(payload)
            if (!verifier.verify(signature)) return rejected(LivePlanApprovalFailure.SIGNATURE_INVALID)
            return Refinement.Refined(payload)
        }

        private fun validFields(document: JsonObject): Boolean {
            val version = document["version"] as? JsonPrimitive ?: return false
            return !version.isString &&
                version.intOrNull == 1 &&
                FIELDS.drop(1).all { (document[it] as? JsonPrimitive)?.isString == true }
        }

        private fun rejected(failure: LivePlanApprovalFailure) = Refinement.Rejected(failure)

        private val FIELDS =
            listOf(
                "version",
                "operation",
                "root",
                "host",
                "planId",
                "challenge",
                "threadId",
                "turnId",
                "callId",
                "keyId",
            )
    }
}
