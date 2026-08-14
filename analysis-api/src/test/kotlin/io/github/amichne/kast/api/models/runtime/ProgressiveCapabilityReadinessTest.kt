package io.github.amichne.kast.api.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProgressiveCapabilityReadinessTest {
    @Test
    fun `compiler and workspace files are available while persisted lanes continue building`() {
        val readiness = progressiveReadiness()

        assertTrue(readiness.compilerLane is CurrentCapabilityLaneReadiness.Available)
        assertTrue(readiness.workspaceFilesLane is CurrentCapabilityLaneReadiness.Available)
        assertTrue(readiness.sourceIndexLane is RetainedCapabilityLaneReadiness.Building)
        assertTrue(readiness.referencesLane is RetainedCapabilityLaneReadiness.Building)
        assertTrue(readiness.semanticGraphLane is RetainedCapabilityLaneReadiness.Building)
        assertEquals(RuntimeReadinessSummary.NotReady, readiness.summary)

        val wire = Json.encodeToJsonElement(RuntimeReadiness.serializer(), readiness).jsonObject
        assertEquals(
            setOf(
                "runtime",
                "model",
                "workspaceFiles",
                "compiler",
                "sourceIndex",
                "references",
                "semanticGraph",
                "mutation",
            ),
            wire.keys,
        )
        assertEquals("AVAILABLE", wire.lane("compiler").type())
        assertEquals("CURRENT", wire.lane("compiler").evidenceFreshness())
        assertEquals("AVAILABLE", wire.lane("workspaceFiles").type())
    }

    @Test
    fun `building persisted lane retains an explicit previous revision`() {
        val wire = Json.encodeToJsonElement(RuntimeReadiness.serializer(), progressiveReadiness()).jsonObject
        val references = wire.lane("references")
        val fallback = references.getValue("fallback").jsonObject
        val evidence = fallback.getValue("evidence").jsonObject

        assertEquals("BUILDING", references.type())
        assertEquals("PREVIOUS", fallback.type())
        assertEquals("PREVIOUS", evidence.getValue("freshness").jsonPrimitive.content)
        assertEquals(7L, evidence.getValue("revision").jsonPrimitive.content.toLong())
    }

    @Test
    fun `runtime status exposes retained publication separately from current publication`() {
        val previousPublication = PublishedWorkspaceGenerationStatus(
            generation = 7,
            identity = "workspace-identity",
            sourceIndexGeneration = 7,
            sourceRevision = 7,
            referenceRevision = 7,
            graphPublication = PublishedGraphEvidenceStatus.Ready(7),
            sourceIndexSchemaVersion = 3,
            databaseFile = "source-index.db",
            publishedAtEpochMillis = 42,
        )
        val response = RuntimeStatusResponse(
            state = RuntimeState.READY,
            backendName = "indexer",
            backendVersion = "test",
            workspaceRoot = "/workspace",
            readiness = progressiveReadiness(),
            referenceCoverageState = ReferenceCoverageState.QUALIFIED,
            referenceCoverageLimitations = listOf(ReferenceCoverageLimitation.INDEXING_IN_PROGRESS),
            retainedWorkspaceGeneration = RetainedWorkspaceGenerationStatus.Previous(previousPublication),
        )

        val wire = Json.encodeToJsonElement(RuntimeStatusResponse.serializer(), response).jsonObject
        val retained = wire.getValue("retainedWorkspaceGeneration").jsonObject

        assertTrue("publishedWorkspaceGeneration" !in wire)
        assertEquals("PREVIOUS", retained.type())
        assertEquals(
            7L,
            retained.getValue("publication").jsonObject.getValue("generation").jsonPrimitive.content.toLong(),
        )
    }

    @Test
    fun `compiler and mutation reject previous freshness on the wire`() {
        val wire = Json.encodeToJsonElement(RuntimeReadiness.serializer(), progressiveReadiness()).jsonObject

        listOf("compiler", "mutation").forEach { laneName ->
            val previous = wire.withLaneFreshness(laneName, "PREVIOUS")
            assertThrows<SerializationException>(laneName) {
                Json.decodeFromJsonElement(RuntimeReadiness.serializer(), previous)
            }
        }
    }

    @Test
    fun `legacy lanes are derived projections and are not serialized`() {
        val readiness = progressiveReadiness()

        assertEquals(RuntimeReadinessLane.Ready, readiness.runtime)
        assertEquals(RuntimeReadinessLane.Ready, readiness.model)
        assertTrue(readiness.references is RuntimeReadinessLane.InProgress)
        assertTrue(readiness.semanticGraph is RuntimeReadinessLane.InProgress)
        assertEquals(RuntimeReadinessLane.Ready, readiness.mutation)

        val wire = Json.encodeToJsonElement(RuntimeReadiness.serializer(), readiness).jsonObject
        assertTrue(wire.keys.none { it.startsWith("legacy") })
    }

    @Test
    fun `lane revisions reject non-positive boundary values`() {
        listOf(0L, -1L).forEach { invalid ->
            assertTrue(EvidenceRevision.parse(invalid) is EvidenceRevisionResolution.Rejected)
        }

        val wire = Json.encodeToString(RuntimeReadiness.serializer(), progressiveReadiness())
            .replaceFirst("\"revision\":11", "\"revision\":0")
        assertThrows<SerializationException> {
            Json.decodeFromString(RuntimeReadiness.serializer(), wire)
        }
    }

    private fun progressiveReadiness(): RuntimeReadiness {
        val current = CurrentCapabilityLaneEvidence.current(revision(11))
        val previous = PreviousCapabilityLaneEvidence.previous(revision(7))
        val indexing = RuntimeReadinessProgress.uncounted(RuntimeProgressStage.SOURCE_INDEX)
        return RuntimeReadiness(
            runtimeLane = CurrentCapabilityLaneReadiness.Available(current),
            modelLane = CurrentCapabilityLaneReadiness.Available(current),
            workspaceFilesLane = CurrentCapabilityLaneReadiness.Available(current),
            compilerLane = CurrentCapabilityLaneReadiness.Available(current),
            sourceIndexLane = RetainedCapabilityLaneReadiness.Building(
                progress = indexing,
                fallback = RetainedCapabilityLaneFallback.None,
            ),
            referencesLane = RetainedCapabilityLaneReadiness.Building(
                progress = RuntimeReadinessProgress.uncounted(RuntimeProgressStage.REFERENCE_INDEX),
                fallback = RetainedCapabilityLaneFallback.Previous(previous),
            ),
            semanticGraphLane = RetainedCapabilityLaneReadiness.Building(
                progress = RuntimeReadinessProgress.uncounted(RuntimeProgressStage.SEMANTIC_GRAPH),
                fallback = RetainedCapabilityLaneFallback.None,
            ),
            mutationLane = CurrentCapabilityLaneReadiness.Available(current),
        )
    }

    private fun revision(value: Long): EvidenceRevision = when (val resolution = EvidenceRevision.parse(value)) {
        is EvidenceRevisionResolution.Resolved -> resolution.revision
        is EvidenceRevisionResolution.Rejected -> error("invalid test revision: ${resolution.failure}")
    }

    private fun JsonObject.lane(name: String): JsonObject = getValue(name).jsonObject

    private fun JsonObject.type(): String = getValue("type").jsonPrimitive.content

    private fun JsonObject.evidenceFreshness(): String =
        getValue("evidence").jsonObject.getValue("freshness").jsonPrimitive.content

    private fun JsonObject.withLaneFreshness(laneName: String, freshness: String): JsonObject {
        val lane = lane(laneName)
        val evidence = lane.getValue("evidence").jsonObject
        val replacedEvidence = JsonObject(evidence + ("freshness" to JsonPrimitive(freshness)))
        val replacedLane = JsonObject(lane + ("evidence" to replacedEvidence))
        return JsonObject(this + (laneName to replacedLane))
    }
}
