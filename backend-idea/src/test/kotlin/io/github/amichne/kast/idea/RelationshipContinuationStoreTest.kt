package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.result.CallRelation
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ImplementationRelation
import io.github.amichne.kast.api.contract.result.RelationTraversalFamily
import io.github.amichne.kast.api.contract.result.RelationTraversalHandle
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.TypeHierarchyRelation
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.contract.skill.WrapperCallDirection
import io.github.amichne.kast.api.protocol.ConflictException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RelationshipContinuationStoreTest {
    @Test
    fun `relationship pages prove one extra result and never replay or omit records`() {
        val store = store()
        try {
            val records = (0 until 5).map(::hierarchyRecord)
            val query = hierarchyQuery(limit = 2)

            val first = store.hierarchy(query, null, records, generation = 7)
            val firstHandle = requireNotNull(first.page.nextHandle)
            val second = store.hierarchy(query, firstHandle, null, generation = 7)
            val secondHandle = requireNotNull(second.page.nextHandle)
            val third = store.hierarchy(query, secondHandle, null, generation = 7)

            assertEquals(listOf(0, 1), offsets(first.records))
            assertEquals(ResultCardinality.KnownMinimum(3), first.page.cardinality)
            assertEquals(3, first.page.visitedCandidateCount)
            assertEquals(listOf(2, 3), offsets(second.records))
            assertEquals(ResultCardinality.KnownMinimum(5), second.page.cardinality)
            assertEquals(3, second.page.visitedCandidateCount)
            assertEquals(listOf(4), offsets(third.records))
            assertEquals(ResultCardinality.Exact(5), third.page.cardinality)
            assertNull(third.page.nextHandle)
            assertEquals((0 until 5).toList(), offsets(first.records + second.records + third.records))

            assertContinuationFailure("unknown") {
                store.hierarchy(query, firstHandle, null, generation = 7)
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun `oversized traversal state fails before returning a partial page`() {
        val store = store()
        try {
            assertContinuationFailure("traversalStateBudgetReached") {
                store.hierarchy(
                    hierarchyQuery(limit = 2),
                    null,
                    (0..16_384).map(::hierarchyRecord),
                    generation = 1,
                )
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun `handles fail closed for family query and generation mismatch`() {
        val store = store()
        try {
            val query = hierarchyQuery(limit = 1)
            val records = listOf(hierarchyRecord(0), hierarchyRecord(1))

            val familyPage = store.hierarchy(query, null, records, generation = 3)
            val familyHandle = requireNotNull(familyPage.page.nextHandle)
            val wrongFamily = RelationTraversalHandle.create(
                RelationTraversalFamily.IMPLEMENTATIONS,
                familyHandle.opaqueId,
            )
            assertContinuationFailure("familyMismatch") {
                store.hierarchy(query, wrongFamily, null, generation = 3)
            }
            store.hierarchy(query, familyHandle, null, generation = 3)

            val queryPage = store.hierarchy(query, null, records, generation = 3)
            val queryHandle = requireNotNull(queryPage.page.nextHandle)
            assertContinuationFailure("queryMismatch") {
                store.hierarchy(
                    query.copy(direction = TypeHierarchyDirection.SUPERTYPES),
                    queryHandle,
                    null,
                    generation = 3,
                )
            }
            assertContinuationFailure("unknown") {
                store.hierarchy(query, queryHandle, null, generation = 3)
            }

            val generationPage = store.hierarchy(query, null, records, generation = 3)
            val generationHandle = requireNotNull(generationPage.page.nextHandle)
            assertContinuationFailure("generationChanged") {
                store.hierarchy(query, generationHandle, null, generation = 4)
            }
            assertContinuationFailure("unknown") {
                store.hierarchy(query, generationHandle, null, generation = 3)
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun `relationship families isolate identical opaque handles`() {
        val store = store()
        try {
            val callerQuery = callQuery(WrapperCallDirection.INCOMING, limit = 1)
            val first = store.calls(
                callerQuery,
                null,
                listOf(callRecord(0), callRecord(1)),
                generation = 5,
            )
            val callerHandle = requireNotNull(first.page.nextHandle)
            val sameOpaqueCalleeHandle = RelationTraversalHandle.create(
                RelationTraversalFamily.CALLEES,
                callerHandle.opaqueId,
            )

            assertContinuationFailure("unknown") {
                store.calls(
                    callQuery(WrapperCallDirection.OUTGOING, limit = 1),
                    sameOpaqueCalleeHandle,
                    null,
                    generation = 5,
                )
            }
            val callerSecond = store.calls(callerQuery, callerHandle, null, generation = 5)
            assertEquals(1, callerSecond.records.size)
        } finally {
            store.close()
        }
    }

    @Test
    fun `close drains every family specific relationship store`() {
        val store = store()
        val callerQuery = callQuery(WrapperCallDirection.INCOMING, limit = 1)
        val calleeQuery = callQuery(WrapperCallDirection.OUTGOING, limit = 1)
        val implementationQuery = implementationQuery(limit = 1)
        val hierarchyQuery = hierarchyQuery(limit = 1)
        val callerHandle = requireNotNull(
            store.calls(callerQuery, null, listOf(callRecord(0), callRecord(1)), 9).page.nextHandle,
        )
        val calleeHandle = requireNotNull(
            store.calls(calleeQuery, null, listOf(callRecord(2), callRecord(3)), 9).page.nextHandle,
        )
        val implementationHandle = requireNotNull(
            store.implementations(
                implementationQuery,
                null,
                listOf(implementationRecord(0), implementationRecord(1)),
                9,
            ).page.nextHandle,
        )
        val hierarchyHandle = requireNotNull(
            store.hierarchy(
                hierarchyQuery,
                null,
                listOf(hierarchyRecord(0), hierarchyRecord(1)),
                9,
            ).page.nextHandle,
        )

        store.close()

        assertContinuationFailure("unknown") {
            store.calls(callerQuery, callerHandle, null, 9)
        }
        assertContinuationFailure("unknown") {
            store.calls(calleeQuery, calleeHandle, null, 9)
        }
        assertContinuationFailure("unknown") {
            store.implementations(implementationQuery, implementationHandle, null, 9)
        }
        assertContinuationFailure("unknown") {
            store.hierarchy(hierarchyQuery, hierarchyHandle, null, 9)
        }
    }

    private fun store(): RelationshipContinuationStore = RelationshipContinuationStore(
        ServerLimits(
            maxResults = 1_000,
            requestTimeoutMillis = 60_000,
            maxConcurrentRequests = 4,
            continuationCapacity = 16,
        ),
    )

    private fun callQuery(
        direction: WrapperCallDirection,
        limit: Int,
    ): RelationshipContinuationStore.CallQuery = RelationshipContinuationStore.CallQuery(
        selector = selector(SymbolKind.FUNCTION),
        direction = direction,
        depth = 3,
        limit = limit,
    )

    private fun implementationQuery(
        limit: Int,
    ): RelationshipContinuationStore.ImplementationQuery =
        RelationshipContinuationStore.ImplementationQuery(
            selector = selector(SymbolKind.INTERFACE),
            limit = limit,
        )

    private fun hierarchyQuery(limit: Int): RelationshipContinuationStore.HierarchyQuery =
        RelationshipContinuationStore.HierarchyQuery(
            selector = selector(SymbolKind.CLASS),
            direction = TypeHierarchyDirection.SUBTYPES,
            depth = 3,
            limit = limit,
        )

    private fun selector(kind: SymbolKind): KastExactSymbolSelector = KastExactSymbolSelector(
        fqName = "sample.Subject",
        declarationFile = "/workspace/Subject.kt",
        declarationStartOffset = 0,
        kind = kind,
    )

    private fun callRecord(index: Int): CallRelation {
        val identity = identity("Caller", index, SymbolKind.FUNCTION)
        return CallRelation(
            relation = CallRelation.Kind.CALLER,
            relatedSymbol = identity,
            callSite = location(identity.declarationFile.value, index),
            depth = 1,
            containingSymbol = ContainingSymbolEvidence.TopLevel,
        )
    }

    private fun implementationRecord(index: Int): ImplementationRelation {
        val identity = identity("Implementation", index, SymbolKind.CLASS)
        return ImplementationRelation(
            implementation = identity,
            declarationLocation = location(identity.declarationFile.value, index),
        )
    }

    private fun hierarchyRecord(index: Int): TypeHierarchyRelation {
        val identity = identity("Type", index, SymbolKind.CLASS)
        return TypeHierarchyRelation(
            relation = TypeHierarchyRelation.Kind.SUBTYPE,
            relatedSymbol = identity,
            declarationLocation = location(identity.declarationFile.value, index),
            depth = 1,
        )
    }

    private fun identity(prefix: String, index: Int, kind: SymbolKind): SymbolIdentity {
        val file = "/workspace/$prefix$index.kt"
        return SymbolIdentity(
            fqName = "sample.$prefix$index",
            kind = kind,
            declarationFile = NormalizedPath.parse(file),
            declarationStartOffset = NonNegativeInt(index),
        )
    }

    private fun location(file: String, index: Int): Location = Location(
        filePath = file,
        startOffset = index,
        endOffset = index + 1,
        startLine = 1,
        startColumn = 1,
        preview = "declaration $index",
    )

    private fun offsets(records: List<TypeHierarchyRelation>): List<Int> =
        records.map { record -> record.relatedSymbol.declarationStartOffset.value }

    private fun assertContinuationFailure(reason: String, action: () -> Unit) {
        val failure = assertThrows(ConflictException::class.java, action)
        assertEquals(reason, failure.details["continuationFailure"])
    }
}
