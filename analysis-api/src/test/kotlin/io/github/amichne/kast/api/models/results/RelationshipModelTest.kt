package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ContainingSymbolUnavailableReason
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.RelationCursorInvalidReason
import io.github.amichne.kast.api.contract.result.RelationCursorStaleReason
import io.github.amichne.kast.api.contract.result.RelationTraversalFamily
import io.github.amichne.kast.api.contract.result.RelationTraversalHandle
import io.github.amichne.kast.api.contract.result.RelationTraversalPageInfo
import io.github.amichne.kast.api.contract.result.RelationshipCoverageStatus
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.ReferencesResult
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RelationshipModelTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private fun completeEvidence(totalCount: Int): RelationshipResultEvidence.Complete =
        RelationshipResultEvidence.Complete(
            cardinality = ResultCardinality.Exact(totalCount),
            coverage = RelationshipSearchCoverage.complete(),
        )

    private fun resumableEvidence(knownMinimumCount: Int): RelationshipResultEvidence.Resumable =
        RelationshipResultEvidence.Resumable(
            cardinality = ResultCardinality.KnownMinimum(knownMinimumCount),
            coverage = RelationshipSearchCoverage.resumable(),
        )

    @Test
    fun `exact relationship evidence carries complete proof for every coverage dimension`() {
        val evidence = RelationshipResultEvidence.Complete(
            cardinality = ResultCardinality.Exact(0),
            coverage = RelationshipSearchCoverage.complete(),
        )

        assertEquals(RelationshipCoverageStatus.COMPLETE, evidence.coverage.identity)
        assertEquals(RelationshipCoverageStatus.COMPLETE, evidence.coverage.projectScope)
        assertEquals(RelationshipCoverageStatus.COMPLETE, evidence.coverage.sourceSetScope)
        assertEquals(RelationshipCoverageStatus.COMPLETE, evidence.coverage.indexFreshness)
        assertEquals(RelationshipCoverageStatus.COMPLETE, evidence.coverage.backend)
        assertEquals(RelationshipCoverageStatus.COMPLETE, evidence.coverage.requestedFamily)
        assertEquals(emptyList<RelationshipSearchLimitation>(), evidence.coverage.limitations)
        val encoded = json.encodeToString(RelationshipResultEvidence.serializer(), evidence)
        assertTrue(encoded.contains("\"cardinality\":{\"type\":\"EXACT\""), encoded)
        assertTrue(encoded.contains("\"coverage\":{\"type\":\"COMPLETE\""), encoded)
        assertEquals(
            evidence,
            json.decodeFromString(
                RelationshipResultEvidence.serializer(),
                encoded,
            ),
        )
    }

    @Test
    fun `limited relationship evidence preserves a known minimum and closed limitations`() {
        val evidence = RelationshipResultEvidence.Limited(
            cardinality = ResultCardinality.KnownMinimum(3),
            coverage = RelationshipSearchCoverage.limited(
                RelationshipSearchLimitation.SOURCE_SET_EXCLUDED,
                RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE,
            ),
        )

        assertEquals(RelationshipCoverageStatus.EXCLUDED, evidence.coverage.sourceSetScope)
        assertEquals(RelationshipCoverageStatus.PARTIAL, evidence.coverage.requestedFamily)
        assertEquals(3, evidence.cardinality.knownMinimumCount)
        assertEquals(
            listOf(
                RelationshipSearchLimitation.SOURCE_SET_EXCLUDED,
                RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE,
            ),
            evidence.coverage.limitations,
        )
    }

    @Test
    fun `limited relationship coverage rejects an empty limitation claim at the wire boundary`() {
        val malformed = """
            {
              "type": "LIMITED",
              "identity": "COMPLETE",
              "projectScope": "COMPLETE",
              "sourceSetScope": "COMPLETE",
              "indexFreshness": "COMPLETE",
              "backend": "COMPLETE",
              "requestedFamily": "COMPLETE",
              "limitations": []
            }
        """.trimIndent()

        assertThrows<IllegalArgumentException> {
            json.decodeFromString(RelationshipSearchCoverage.serializer(), malformed)
        }
    }

    @Test
    fun `containing symbol evidence preserves the complete declaration anchor`() {
        val identity = SymbolIdentity(
            fqName = "sample.Controller.handle",
            kind = SymbolKind.FUNCTION,
            declarationFile = NormalizedPath.parse("/repo/Controller.kt"),
            declarationStartOffset = NonNegativeInt(10),
            containingType = "sample.Controller",
        )
        val evidence = listOf(
            ContainingSymbolEvidence.Known(identity),
            ContainingSymbolEvidence.TopLevel,
            ContainingSymbolEvidence.Unavailable(
                ContainingSymbolUnavailableReason.NO_SEMANTIC_OWNER,
            ),
        )

        evidence.forEach { expected ->
            val encoded = json.encodeToString(ContainingSymbolEvidence.serializer(), expected)
            assertEquals(
                expected,
                json.decodeFromString(ContainingSymbolEvidence.serializer(), encoded),
            )
        }

        val occurrence = ReferenceOccurrence(
            location = Location(
                filePath = "/repo/Use.kt",
                startOffset = 20,
                endOffset = 26,
                startLine = 2,
                startColumn = 5,
                preview = "handle()",
            ),
            containingSymbol = ContainingSymbolEvidence.Known(identity),
        )
        val encodedOccurrence = json.encodeToString(ReferenceOccurrence.serializer(), occurrence)

        assertEquals(
            occurrence,
            json.decodeFromString(ReferenceOccurrence.serializer(), encodedOccurrence),
        )

        val result = ReferencesResult(
            references = listOf(occurrence),
            evidence = completeEvidence(1),
        )
        assertEquals(listOf(occurrence), result.items)
    }

    @Test
    fun `relationship traversal handles preserve only canonical typed families`() {
        val suffix = "123e4567-e89b-12d3-a456-426614174000"
        val handles = mapOf(
            RelationTraversalFamily.CALLERS to "rth1_callers_$suffix",
            RelationTraversalFamily.CALLEES to "rth1_callees_$suffix",
            RelationTraversalFamily.IMPLEMENTATIONS to "rth1_implementations_$suffix",
            RelationTraversalFamily.HIERARCHY to "rth1_hierarchy_$suffix",
        )

        handles.forEach { (expectedFamily, raw) ->
            val parsed = RelationTraversalHandle.parse(raw)
            assertEquals(expectedFamily, parsed.family)
            assertEquals(raw, parsed.value)
            assertEquals(
                parsed,
                json.decodeFromString(
                    RelationTraversalHandle.serializer(),
                    json.encodeToString(RelationTraversalHandle.serializer(), parsed),
                ),
            )
        }

        assertEquals(
            listOf(RelationCursorStaleReason.GENERATION_CHANGED, RelationCursorStaleReason.EXPIRED),
            RelationCursorStaleReason.entries,
        )
        assertEquals(
            listOf(
                RelationCursorInvalidReason.UNKNOWN_HANDLE,
                RelationCursorInvalidReason.FAMILY_MISMATCH,
                RelationCursorInvalidReason.QUERY_MISMATCH,
            ),
            RelationCursorInvalidReason.entries,
        )

        val invalidHandles = listOf(
            "",
            "rth2_callers_$suffix",
            "rth1_references_$suffix",
            "rth1_callers_${suffix.uppercase()}",
            "rth1_callers_not-a-uuid",
            "rth1_callers_${suffix}_extra",
            "rth1_callers_${suffix}é",
            "rth1_implementations_${suffix}padding",
        )
        invalidHandles.forEach { raw ->
            assertThrows<IllegalArgumentException> { RelationTraversalHandle.parse(raw) }
        }
    }

    @Test
    fun `relationship pages require cumulative continuation and visit budget proof`() {
        val nextHandle = RelationTraversalHandle.parse(
            "rth1_callers_123e4567-e89b-12d3-a456-426614174000",
        )
        val continued = RelationTraversalPageInfo.create(
            evidence = completeEvidence(6),
            returnedCount = 3,
            returnedBefore = 2,
            visitedCandidateCount = 8,
            candidateVisitLimit = 8,
            nextHandle = nextHandle,
        )

        assertEquals(3, continued.returnedCount)
        assertEquals(true, continued.truncated)
        assertEquals(nextHandle, continued.nextHandle)
        val encoded = json.encodeToString(RelationTraversalPageInfo.serializer(), continued)
        assertTrue(encoded.contains("\"evidence\":{\"type\":\"COMPLETE\""), encoded)
        assertFalse(encoded.contains("returnedBefore"))
        assertFalse(encoded.contains("candidateVisitLimit"))

        val exact = RelationTraversalPageInfo.create(
            evidence = completeEvidence(5),
            returnedCount = 3,
            returnedBefore = 2,
            visitedCandidateCount = 3,
            candidateVisitLimit = 8,
            nextHandle = null,
        )
        assertEquals(false, exact.truncated)

        assertThrows<IllegalArgumentException> {
            RelationTraversalPageInfo.create(
                evidence = completeEvidence(1),
                returnedCount = 2,
                returnedBefore = 0,
                visitedCandidateCount = 2,
                candidateVisitLimit = 2,
                nextHandle = null,
            )
        }
        assertThrows<IllegalArgumentException> {
            RelationTraversalPageInfo.create(
                evidence = completeEvidence(5),
                returnedCount = 3,
                returnedBefore = 2,
                visitedCandidateCount = 3,
                candidateVisitLimit = 3,
                nextHandle = nextHandle,
            )
        }
        assertThrows<IllegalArgumentException> {
            RelationTraversalPageInfo.create(
                evidence = completeEvidence(4),
                returnedCount = 3,
                returnedBefore = 0,
                visitedCandidateCount = 9,
                candidateVisitLimit = 8,
                nextHandle = nextHandle,
            )
        }
    }

    @Test
    fun `relationship page wire form rejects token truncation disagreement`() {
        val handle = "rth1_callers_123e4567-e89b-12d3-a456-426614174000"
        val tokenWithoutTruncation =
            """{"evidence":{"type":"COMPLETE","cardinality":{"type":"EXACT","totalCount":2},"coverage":{"type":"COMPLETE","identity":"COMPLETE","projectScope":"COMPLETE","sourceSetScope":"COMPLETE","indexFreshness":"COMPLETE","backend":"COMPLETE","requestedFamily":"COMPLETE","limitations":[]}},"returnedCount":1,"visitedCandidateCount":1,"truncated":false,"nextHandle":"$handle"}"""
        val truncationWithoutToken =
            """{"evidence":{"type":"COMPLETE","cardinality":{"type":"EXACT","totalCount":2},"coverage":{"type":"COMPLETE","identity":"COMPLETE","projectScope":"COMPLETE","sourceSetScope":"COMPLETE","indexFreshness":"COMPLETE","backend":"COMPLETE","requestedFamily":"COMPLETE","limitations":[]}},"returnedCount":1,"visitedCandidateCount":1,"truncated":true}"""

        assertThrows<IllegalArgumentException> {
            json.decodeFromString(RelationTraversalPageInfo.serializer(), tokenWithoutTruncation)
        }
        assertThrows<IllegalArgumentException> {
            json.decodeFromString(RelationTraversalPageInfo.serializer(), truncationWithoutToken)
        }
    }
}
