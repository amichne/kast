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
import io.github.amichne.kast.api.contract.result.ResultCardinality
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RelationshipModelTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
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
            cardinality = ResultCardinality.KnownMinimum(6),
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
        assertFalse(encoded.contains("returnedBefore"))
        assertFalse(encoded.contains("candidateVisitLimit"))

        val exact = RelationTraversalPageInfo.create(
            cardinality = ResultCardinality.Exact(5),
            returnedCount = 3,
            returnedBefore = 2,
            visitedCandidateCount = 3,
            candidateVisitLimit = 8,
            nextHandle = null,
        )
        assertEquals(false, exact.truncated)

        assertThrows<IllegalArgumentException> {
            RelationTraversalPageInfo.create(
                cardinality = ResultCardinality.Exact(1),
                returnedCount = 2,
                returnedBefore = 0,
                visitedCandidateCount = 2,
                candidateVisitLimit = 2,
                nextHandle = null,
            )
        }
        assertThrows<IllegalArgumentException> {
            RelationTraversalPageInfo.create(
                cardinality = ResultCardinality.KnownMinimum(5),
                returnedCount = 3,
                returnedBefore = 2,
                visitedCandidateCount = 3,
                candidateVisitLimit = 3,
                nextHandle = nextHandle,
            )
        }
        assertThrows<IllegalArgumentException> {
            RelationTraversalPageInfo.create(
                cardinality = ResultCardinality.KnownMinimum(4),
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
            """{"cardinality":{"type":"KNOWN_MINIMUM","knownMinimumCount":2},"returnedCount":1,"visitedCandidateCount":1,"truncated":false,"nextHandle":"$handle"}"""
        val truncationWithoutToken =
            """{"cardinality":{"type":"KNOWN_MINIMUM","knownMinimumCount":2},"returnedCount":1,"visitedCandidateCount":1,"truncated":true}"""

        assertThrows<IllegalArgumentException> {
            json.decodeFromString(RelationTraversalPageInfo.serializer(), tokenWithoutTruncation)
        }
        assertThrows<IllegalArgumentException> {
            json.decodeFromString(RelationTraversalPageInfo.serializer(), truncationWithoutToken)
        }
    }
}
