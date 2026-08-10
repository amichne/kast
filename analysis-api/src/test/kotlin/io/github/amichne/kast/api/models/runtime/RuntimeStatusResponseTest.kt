package io.github.amichne.kast.api.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RuntimeStatusResponseTest {
    @Test
    fun `serialization rejects contradictory reference coverage facts`() {
        invalidCoverageFacts.forEach { coverageFacts ->
            assertThrows<IllegalArgumentException>(coverageFacts) {
                Json.decodeFromString<RuntimeStatusResponse>(runtimeStatusJson(coverageFacts))
            }
        }
    }

    @Test
    fun `serialization parses compatible wire facts into one coverage value`() {
        val response = Json.decodeFromString<RuntimeStatusResponse>(
            runtimeStatusJson(
                """
                "referenceCoverageState": "QUALIFIED",
                "referenceCoverageLimitations": ["NONCRITICAL_STAGE_GAP"],
                """.trimIndent(),
            ),
        )

        assertTrue(response.referenceCoverage.indexReady)
        assertEquals(ReferenceCoverageState.QUALIFIED, response.referenceCoverage.state)
        assertEquals(
            listOf(ReferenceCoverageLimitation.NONCRITICAL_STAGE_GAP),
            response.referenceCoverage.limitations,
        )
        val encoded = Json.encodeToJsonElement(RuntimeStatusResponse.serializer(), response).jsonObject
        assertFalse("referenceIndexReady" in encoded)
        assertFalse("referenceCoverage" in encoded)
    }

    @Test
    fun `legacy boolean lifecycle fields are rejected`() {
        assertThrows<Exception> {
            Json { ignoreUnknownKeys = false }.decodeFromString<RuntimeStatusResponse>(
                runtimeStatusJson("\"healthy\": true,"),
            )
        }
    }

    @Test
    fun `serialization preserves the exact admitted workspace generation`() {
        val published = PublishedWorkspaceGenerationStatus(
            generation = 7,
            identity = "workspace-state",
            sourceIndexGeneration = 19,
            sourceRevision = 19,
            referenceRevision = 19,
            graphPublication = PublishedGraphEvidenceStatus.Ready(19),
            sourceIndexSchemaVersion = 3,
            databaseFile = "source-index.db",
            repositoryOverlayFile = "repository-overlay.json",
            publishedAtEpochMillis = 42,
        )
        val response = RuntimeStatusResponse(
            state = RuntimeState.READY,
            backendName = "indexer",
            backendVersion = "test",
            workspaceRoot = "/workspace",
            publishedWorkspaceGeneration = published,
            readiness = RuntimeReadiness.ready(),
        )

        val encoded = Json.encodeToString(RuntimeStatusResponse.serializer(), response)
        val decoded = Json.decodeFromString<RuntimeStatusResponse>(encoded)

        assertEquals(published, decoded.publishedWorkspaceGeneration)
    }

    @Test
    fun `layered readiness keeps runtime model graph and references distinct`() {
        val response = Json.decodeFromString<RuntimeStatusResponse>(
            runtimeStatusJson(
                "\"referenceCoverageState\": \"UNAVAILABLE\",\n" +
                    "\"referenceCoverageLimitations\": [],",
                references = "BLOCKED",
            ),
        )

        assertTrue(response.readiness.runtime is RuntimeReadinessLane.Ready)
        assertTrue(response.readiness.model is RuntimeReadinessLane.Ready)
        assertTrue(response.readiness.semanticGraph is RuntimeReadinessLane.Ready)
        assertTrue(response.readiness.references is RuntimeReadinessLane.Blocked)
        assertEquals(RuntimeReadinessSummary.NotReady, response.readiness.summary)
        assertEquals(RuntimeReadinessSummary.NotReady, response.readiness.summary)
    }

    @Test
    fun `runtime status requires tagged readiness lanes`() {
        val payload = runtimeStatusJson("").replace(Regex(",?\\s*\"readiness\"[\\s\\S]*?\n  },\n  \"schemaVersion\""), "\n  \"schemaVersion\"")
        assertThrows<Exception> { Json.decodeFromString<RuntimeStatusResponse>(payload) }
    }

    @Test
    fun `qualified reference coverage rejects an unrelated progress stage`() {
        val coverage = ReferenceCoverage.qualified(
            limitations = listOf(ReferenceCoverageLimitation.INDEXING_IN_PROGRESS),
            indexReady = false,
        )
        val actualLane = RuntimeReadinessLane.inProgress(RuntimeProgressStage.SOURCE_INDEX)
        val readiness = RuntimeReadiness(
            runtime = RuntimeReadinessLane.Ready,
            model = RuntimeReadinessLane.inProgress(RuntimeProgressStage.GRADLE_IMPORT),
            references = actualLane,
            semanticGraph = RuntimeReadinessLane.Ready,
            mutation = RuntimeReadinessLane.Ready,
        )

        val failure = assertThrows<RuntimeStatusConsistencyException> {
            RuntimeStatusResponse(
                state = RuntimeState.INDEXING,
                backendName = "indexer",
                backendVersion = "test",
                workspaceRoot = "/workspace",
                referenceCoverageState = coverage.state,
                referenceCoverageLimitations = coverage.limitations,
                readiness = readiness,
            )
        }.failure

        assertEquals(
            RuntimeStatusConsistencyFailure.ReferenceCoverageMismatch(
                ReferenceReadinessAlignmentFailure.Mismatch(
                    RuntimeReadinessLane.inProgress(RuntimeProgressStage.REFERENCE_INDEX),
                    actualLane,
                ),
            ),
            failure,
        )
    }

    @Test
    fun `typed progress is derived from closed work and timing evidence`() {
        val progress = RuntimeReadinessProgress.derive(
            stage = RuntimeProgressStage.GRADLE_IMPORT,
            work = RuntimeProgressWork.pending(NonNegativeInt(2)),
            timing = RuntimeProgressTiming.between(
                stageStartedAt = Instant.ofEpochMilli(0),
                lastProgressAt = Instant.ofEpochMilli(7),
                observedAt = Instant.ofEpochMilli(10),
            ),
        )

        assertEquals(0, progress.completedUnits)
        assertEquals(2, progress.totalUnits)
        assertEquals(10, progress.elapsedMillis)
        assertEquals(3, progress.noProgressMillis)
    }

    @Test
    fun `published workspace generation rejects non-canonical database paths`() {
        assertThrows<IllegalArgumentException> {
            PublishedWorkspaceGenerationStatus(
                generation = 1,
                identity = "workspace-state",
                sourceIndexGeneration = 1,
                sourceRevision = 1,
                referenceRevision = 1,
                graphPublication = PublishedGraphEvidenceStatus.Ready(1),
                sourceIndexSchemaVersion = 1,
                databaseFile = "../source-index.db",
                publishedAtEpochMillis = 1,
            )
        }
    }

    private fun runtimeStatusJson(coverageFacts: String, references: String = "READY"): String =
        """
        {
          "state": "READY",
          "backendName": "indexer",
          "backendVersion": "test",
          "workspaceRoot": "/workspace",
          $coverageFacts
          "readiness": {
            "runtime": {"type": "READY"},
            "model": {"type": "READY"},
            "references": {"type": "$references"},
            "semanticGraph": {"type": "READY"},
            "mutation": {"type": "READY"}
          },
          "schemaVersion": 1
        }
        """.trimIndent()

    private companion object {
        val invalidCoverageFacts = listOf(
            """
            "referenceIndexReady": false,
            "referenceCoverageState": "COMPLETE",
            "referenceCoverageLimitations": [],
            """.trimIndent(),
            """
            "referenceIndexReady": true,
            "referenceCoverageState": "COMPLETE",
            "referenceCoverageLimitations": ["NONCRITICAL_STAGE_GAP"],
            """.trimIndent(),
            """
            "referenceIndexReady": false,
            "referenceCoverageState": "QUALIFIED",
            "referenceCoverageLimitations": [],
            """.trimIndent(),
            """
            "referenceIndexReady": true,
            "referenceCoverageState": "INCOMPLETE",
            "referenceCoverageLimitations": ["CRITICAL_STAGE_GAP"],
            """.trimIndent(),
            """
            "referenceIndexReady": false,
            "referenceCoverageState": "INCOMPLETE",
            "referenceCoverageLimitations": [],
            """.trimIndent(),
            """
            "referenceIndexReady": false,
            "referenceCoverageState": "QUALIFIED",
            "referenceCoverageLimitations": ["CRITICAL_STAGE_GAP"],
            """.trimIndent(),
            """
            "referenceIndexReady": false,
            "referenceCoverageState": "INCOMPLETE",
            "referenceCoverageLimitations": ["INDEXING_IN_PROGRESS"],
            """.trimIndent(),
            """
            "referenceIndexReady": false,
            "referenceCoverageState": "UNAVAILABLE",
            "referenceCoverageLimitations": ["NONCRITICAL_STAGE_GAP"],
            """.trimIndent(),
            """
            "referenceIndexReady": false,
            "referenceCoverageState": "QUALIFIED",
            "referenceCoverageLimitations": ["INDEXING_IN_PROGRESS", "INDEXING_IN_PROGRESS"],
            """.trimIndent(),
            """
            "referenceIndexReady": true,
            "referenceCoverageState": "QUALIFIED",
            "referenceCoverageLimitations": ["INDEXING_IN_PROGRESS"],
            """.trimIndent(),
            """
            "referenceIndexReady": false,
            "referenceCoverageState": "QUALIFIED",
            "referenceCoverageLimitations": ["NONCRITICAL_STAGE_GAP"],
            """.trimIndent(),
        )
    }
}
