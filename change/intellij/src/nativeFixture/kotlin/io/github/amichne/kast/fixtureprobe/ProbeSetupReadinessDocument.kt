package io.github.amichne.kast.fixtureprobe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal fun readinessDocument(readiness: ProbeSetupObservation): JsonElement =
    readinessJson.encodeToJsonElement(
        ProbeSetupReadinessDocument.serializer(),
        ProbeSetupReadinessDocument(
            sourceProvenance = readiness.provenance,
            import = readiness.import,
            pushedPropertiesDrain = readiness.drain,
            indexing = readiness.after.indexing,
            refreshScanning = readiness.after.refresh.scanning,
            refreshEventProcessing = readiness.after.refresh.processing,
            generationBefore = readiness.before.generation,
            generationAfter = readiness.after.generation,
        ),
    )

private val readinessJson = Json { encodeDefaults = true }

@Serializable
private data class ProbeSetupReadinessDocument(
    val smartMode: String = "SMART",
    val externalTasks: String = "IDLE",
    val gradleModule: String = "OBSERVED",
    val sourceProvenance: ProbeSourceProvenance,
    val import: ProbeSetupImportEvidence,
    val vfsRefresh: String = "COMPLETED",
    val quietWindowMillis: Long = SETUP_QUIET_WINDOW_MILLIS,
    val scope: String = "OBSERVED_SETUP_ONLY",
    val pushedPropertiesDrain: ProbeSetupDrainState,
    val indexing: ProbeSetupIndexingState,
    val refreshScanning: ProbeSetupQueueState,
    val refreshEventProcessing: ProbeSetupQueueState,
    val generationBefore: ProbeSetupGeneration,
    val generationAfter: ProbeSetupGeneration,
)
