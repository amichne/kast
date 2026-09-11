package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Terminal recovery refusal owns a separate plan, so it cannot poison later successful rollback cases. */
internal class NativeDivergentRecoveryWorkflow(
    private val controls: NativeFixtureControls,
    private val source: Path,
    private val evidence: NativeChangeEvidence,
) {
    suspend fun run(peer: NativeChangePeer) {
        evidence.record("recovery-preserves-divergent-content", NativeCaseOutcome.UNQUALIFIED)
        val found = NativeChangeRead(peer).searchClass()
        val reference = ((found["items"] as JsonArray).single() as JsonObject).textAt("symbol_ref")
        val planned = peer.call("change_plan", nativePlanArguments(reference, "fun acceptanceDivergent() = value"))
        demand(!planned.rejected(), NativeFailure.PROVIDER_REJECTED)
        val arguments =
            Json.encodeToJsonElement(
                    NativeRecoveryPlanIdentity.serializer(),
                    NativeRecoveryPlanIdentity(planned.document().textAt("planIdentity")),
                )
                .jsonObject
        val applied = peer.call("change_apply", arguments)
        demand(
            !applied.rejected() && applied.document()["state"] == JsonPrimitive("verified"),
            NativeFailure.VERIFIED_RECEIPT_MISSING,
        )
        val postimage = Files.readAllBytes(source)
        controls.restart()
        val divergent = postimage + "\n// acceptance-owned user content\n".toByteArray()
        Files.write(source, divergent)
        val unsafe = peer.call("change_recover", arguments)
        evidence.expectRecoveryRequired("recovery-preserves-divergent-content", unsafe)
        demand(Files.readAllBytes(source).contentEquals(divergent), NativeFailure.SOURCE_CHANGED)
        evidence.record(
            "recovery-preserves-divergent-content",
            NativeCaseOutcome.PASSED,
            Json.encodeToJsonElement(
                    NativeDivergentRecoveryEvidence.serializer(),
                    NativeDivergentRecoveryEvidence(sha256(postimage), sha256(divergent)),
                )
                .jsonObject,
        )
    }
}

@Serializable private data class NativeRecoveryPlanIdentity(val planIdentity: String)

@Serializable
private data class NativeDivergentRecoveryEvidence(val sourcePreimageSha256: String, val sourcePostimageSha256: String)
