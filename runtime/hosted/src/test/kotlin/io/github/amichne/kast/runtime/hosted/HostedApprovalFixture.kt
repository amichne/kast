package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.change.apply.BrokerApprovalVerificationKey
import io.github.amichne.kast.change.apply.LiveApprovalChallenge
import io.github.amichne.kast.change.apply.LiveChangeEffect
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.kernel.Refinement
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.assertInstanceOf

internal class HostedApprovalFixture {
    val plan =
        LiveAddDeclarationPlanCodec.decode(
                checkNotNull(javaClass.getResource("/live-add-declaration-plan-v1.json")).readText()
            )
            .approvalRefined()
    val owner = plan.basis.observation.reference.host
    val pair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val key = BrokerApprovalVerificationKey.decodePinnedX509(pair.public.encoded).approvalRefined()

    fun signed(
        challenge: LiveApprovalChallenge,
        operation: LiveChangeEffect = LiveChangeEffect.CHANGE_APPLY,
        signer: KeyPair = pair,
        changes: Map<String, JsonElement> = emptyMap(),
    ): String {
        val payload = buildJsonObject {
            put("version", 1)
            put("operation", operation.name)
            put("root", plan.basis.observation.reference.workspaceRoot.value)
            put("host", owner.value.toString())
            put("planId", plan.planId.value)
            put("challenge", challenge.value)
            put("threadId", "thread-1")
            put("turnId", "turn-1")
            put("callId", "call-1")
            put(
                "keyId",
                MessageDigest.getInstance("SHA-256").digest(signer.public.encoded).joinToString("") {
                    "%02x".format(java.util.Locale.ROOT, it)
                },
            )
        }
        val bytes = JsonObject(payload + changes).toString().toByteArray()
        val signature =
            Signature.getInstance("Ed25519").run {
                initSign(signer.private)
                update(bytes)
                sign()
            }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "${encoder.encodeToString(bytes)}.${encoder.encodeToString(signature)}"
    }
}

internal fun <T, F> Refinement<T, F>.approvalRefined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
