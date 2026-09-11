package io.github.amichne.kast.fixtureprobe

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun encodeProbeResponse(id: UUID, command: String, result: ProbeExecution): String = buildJsonObject {
    put("version", PROBE_VERSION)
    put("id", id.toString())
    put("command", command)
    when (result) {
        is ProbeExecution.Rejected -> {
            put("outcome", "REJECTED")
            put("failure", result.failure.name)
        }
        is ProbeExecution.EffectUncertain -> {
            put("outcome", "EFFECT_UNCERTAIN")
            put("failure", result.failure.name)
        }
        is ProbeExecution.IndexingHeld -> {
            put("outcome", "INDEXING_HELD")
            put("evidence", evidenceDocument(result.evidence))
            put("indexing", heldIndexingDocument())
        }
        is ProbeExecution.IndexingReleased -> {
            put("outcome", "INDEXING_RELEASED")
            put("evidence", evidenceDocument(result.evidence))
            put("indexing", buildJsonObject { put("state", "SMART") })
        }
        is ProbeExecution.SetupReady -> {
            put("outcome", "SETUP_READY")
            put("evidence", evidenceDocument(result.evidence))
            put("readiness", readinessDocument(result.readiness))
        }
        is ProbeExecution.Completed -> {
            put("outcome", "COMPLETED")
            put("evidence", evidenceDocument(result.evidence))
        }
        is ProbeExecution.BarrierArmed -> {
            put("outcome", "BARRIER_ARMED")
            put("barrierId", result.barrierId.toString())
            put("evidence", evidenceDocument(result.evidence))
        }
        is ProbeExecution.LifecycleCompleted -> {
            put("outcome", "LIFECYCLE_COMPLETED")
            put("lifecycle", result.lifecycle.name)
            put("evidence", evidenceDocument(result.evidence))
        }
    }
}
    .toString()

private fun heldIndexingDocument() = buildJsonObject {
    put("state", "DUMB")
    put("maximumHoldMillis", MAXIMUM_INDEXING_HOLD_MILLIS)
}

private fun readinessDocument(readiness: ProbeSetupObservation) = buildJsonObject {
    put("smartMode", "SMART")
    put("externalTasks", "IDLE")
    put("gradleModule", "OBSERVED")
    put("sourceProvenance", readiness.provenance.name)
    put("import", readiness.import.name)
    put("vfsRefresh", "COMPLETED")
    put("quietWindowMillis", SETUP_QUIET_WINDOW_MILLIS)
    put("scope", "OBSERVED_SETUP_ONLY")
}

private fun evidenceDocument(evidence: ProbeEvidence) = buildJsonObject {
    put("savedSha256", evidence.saved.value)
    put("documentSha256", evidence.document.value)
    put("documentState", evidence.documentState.name)
    put("syntax", evidence.syntax.name)
    put("undo", evidence.undo.name)
    put(
        "declarations",
        JsonArray(
            evidence.declarations.map { declaration ->
                buildJsonObject {
                    put("name", declaration.name)
                    put("container", declaration.container)
                    put("kind", declaration.kind.name)
                }
            }
        ),
    )
}
