package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NativeModelMovementWorkflow(
    private val controls: NativeFixtureControls,
    private val source: Path,
    private val evidence: NativeChangeEvidence,
) {
    suspend fun run(peer: NativeChangePeer) {
        evidence.record("generated-target-refusal", NativeCaseOutcome.UNQUALIFIED)
        evidence.record("model-movement-refusal", NativeCaseOutcome.UNQUALIFIED)
        val workspace = source.parent.parent.parent.parent
        val movement = workspace.resolve("native-fixture-sources/movement/NativeModelMovementTarget.kt")
        val generated = workspace.resolve("native-fixture-sources/generated/NativeGeneratedTarget.kt")
        val before = listOf(source, movement, generated).associateWith(Files::readAllBytes)
        val read = NativeChangeRead(peer)
        read.search(tool = "search_classes", field = "class_name", name = "NativeGeneratedTarget", count = 0)
        val found =
            read.search(tool = "search_classes", field = "class_name", name = "NativeModelMovementTarget", count = 1)
        val reference = ((found["items"] as JsonArray).single() as JsonObject).textAt("symbol_ref")
        val planned = peer.call("change_plan", nativePlanArguments(reference, "fun modelMoved() = value"))
        demand(!planned.rejected(), NativeFailure.PROVIDER_REJECTED)
        val plan = planned.document()
        demand(plan.objectAt("live") == found.objectAt("live"), NativeFailure.SOURCE_CHANGED)
        unchanged(before)
        controls.amendGeneratedProvenance(sha256(Files.readAllBytes(source)))
        val fresh =
            read.search(tool = "search_classes", field = "class_name", name = "NativeModelMovementTarget", count = 0)
        read.search(tool = "search_classes", field = "class_name", name = "NativeGeneratedTarget", count = 0)
        demand(
            fresh.objectAt("live")["host"] == found.objectAt("live")["host"] &&
                fresh.objectAt("live")["epoch"] != found.objectAt("live")["epoch"],
            NativeFailure.EPOCH_DID_NOT_CHANGE,
        )
        val stale = peer.call("change_plan", nativePlanArguments(reference, "fun staleMovement() = value"))
        evidence.expectRejection("generated-target-refusal", NativeExpectedRejection.EXACT_SYMBOL_REQUIRED, stale)
        unchanged(before)
        recordGeneratedRefusal(reference, before.getValue(generated))
        val applied = peer.call("change_apply", buildJsonObject { put("planIdentity", plan.textAt("planIdentity")) })
        evidence.expectRejection("model-movement-refusal", NativeExpectedRejection.CONTENT_CHANGED, applied)
        unchanged(before)
        evidence.record(
            "model-movement-refusal",
            NativeCaseOutcome.PASSED,
            buildJsonObject {
                put("before", found.objectAt("live"))
                put("after", fresh.objectAt("live"))
                put("sourcePreimageSha256", sha256(before.getValue(movement)))
                put("sourcePostimageSha256", sha256(Files.readAllBytes(movement)))
                put("planIdentitySha256", sha256(plan.textAt("planIdentity").toByteArray()))
            },
        )
    }

    private fun recordGeneratedRefusal(reference: String, generated: ByteArray) {
        evidence.record(
            "generated-target-refusal",
            NativeCaseOutcome.PASSED,
            buildJsonObject {
                put("sourceSha256", sha256(generated))
                put("referenceSha256", sha256(reference.toByteArray()))
            },
        )
    }

    private fun unchanged(images: Map<Path, ByteArray>) {
        demand(
            images.all { (path, bytes) -> Files.readAllBytes(path).contentEquals(bytes) },
            NativeFailure.SOURCE_CHANGED,
        )
    }
}
