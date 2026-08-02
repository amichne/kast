package io.github.amichne.kast.api.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                "referenceIndexReady": true,
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
        assertTrue(encoded.getValue("referenceIndexReady").jsonPrimitive.boolean)
        assertEquals("QUALIFIED", encoded.getValue("referenceCoverageState").jsonPrimitive.content)
        assertEquals(
            listOf("NONCRITICAL_STAGE_GAP"),
            encoded.getValue("referenceCoverageLimitations").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse("referenceCoverage" in encoded)
    }

    @Test
    fun `serialization derives coverage for legacy readiness-only payloads`() {
        val ready = Json.decodeFromString<RuntimeStatusResponse>(legacyRuntimeStatusJson(referenceIndexReady = true))
        val unavailable = Json.decodeFromString<RuntimeStatusResponse>(
            legacyRuntimeStatusJson(referenceIndexReady = false),
        )

        assertEquals(ReferenceCoverageState.COMPLETE, ready.referenceCoverage.state)
        assertTrue(ready.referenceCoverage.indexReady)
        assertEquals(ReferenceCoverageState.UNAVAILABLE, unavailable.referenceCoverage.state)
        assertFalse(unavailable.referenceCoverage.indexReady)
    }

    private fun runtimeStatusJson(coverageFacts: String): String =
        """
        {
          "state": "READY",
          "healthy": true,
          "active": true,
          "indexing": false,
          "backendName": "indexer",
          "backendVersion": "test",
          "workspaceRoot": "/workspace",
          $coverageFacts
          "schemaVersion": 1
        }
        """.trimIndent()

    private fun legacyRuntimeStatusJson(referenceIndexReady: Boolean): String =
        """
        {
          "state": "READY",
          "healthy": true,
          "active": true,
          "indexing": false,
          "backendName": "indexer",
          "backendVersion": "test",
          "workspaceRoot": "/workspace",
          "referenceIndexReady": $referenceIndexReady,
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
