package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NativeIndexingWorkflow(
    private val controls: NativeFixtureControls,
    private val source: Path,
    private val evidence: NativeChangeEvidence,
) {
    suspend fun run(peer: NativeChangePeer, arguments: JsonObject) {
        evidence.record("indexing-policy-refusal", NativeCaseOutcome.UNQUALIFIED)
        val before = Files.readAllBytes(source)
        val held = NativeIndexingHold.admit(controls.controlProbe("HOLD_INDEXING", sha256(before)))
        try {
            demand(held.source.savedSha256 == sha256(before), NativeFailure.SOURCE_CHANGED)
            val rejected = peer.call("change_plan", arguments)
            evidence.expectRejection("indexing-policy-refusal", NativeExpectedRejection.WORKSPACE_NOT_READY, rejected)
            demand(Files.readAllBytes(source).contentEquals(before), NativeFailure.SOURCE_CHANGED)
        } finally {
            held.release(controls)
        }
        evidence.record(
            "indexing-policy-refusal",
            NativeCaseOutcome.PASSED,
            buildJsonObject {
                put("sourceSha256", sha256(before))
                put("indexingState", "DUMB")
                put("afterIndexingState", "SMART")
            },
        )
    }
}

internal class NativeIndexingHold private constructor(val source: NativeProbeEvidence) {
    suspend fun release(controls: NativeFixtureControls) {
        val response = controls.controlProbe("RELEASE_INDEXING", source.savedSha256)
        admitEnvelope(response, "RELEASE_INDEXING", "INDEXING_RELEASED")
        demand(
            response.objectAt("indexing") == buildJsonObject { put("state", "SMART") },
            NativeFailure.PROBE_REJECTED,
        )
        val after = NativeFixtureControls.decodeEvidence(response)
        demand(after.savedSha256 == source.savedSha256, NativeFailure.SOURCE_CHANGED)
    }

    companion object {
        fun admit(response: JsonObject): NativeIndexingHold {
            admitEnvelope(response, "HOLD_INDEXING", "INDEXING_HELD")
            demand(
                response.objectAt("indexing") ==
                    buildJsonObject {
                        put("state", "DUMB")
                        put("maximumHoldMillis", 10000)
                    },
                NativeFailure.PROBE_REJECTED,
            )
            val source = NativeFixtureControls.decodeEvidence(response)
            demand(
                source.documentState == NativeDocumentState.SAVED_COMMITTED &&
                    source.savedSha256 == source.documentSha256,
                NativeFailure.PROBE_REJECTED,
            )
            return NativeIndexingHold(source)
        }

        private fun admitEnvelope(response: JsonObject, command: String, outcome: String) {
            demand(
                response.keys == setOf("version", "id", "command", "outcome", "evidence", "indexing") &&
                    response["version"] == JsonPrimitive(1) &&
                    response.textAt("command") == command &&
                    response.textAt("outcome") == outcome,
                NativeFailure.PROBE_REJECTED,
            )
        }
    }
}
