package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.RelationTraversalPageInfo
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.selector.DigestSelectorHandleAuthority
import io.github.amichne.kast.api.contract.selector.SelectorHandleAuthority
import io.github.amichne.kast.api.contract.skill.KastCallersQuery
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.contract.skill.KastHierarchyQuery
import io.github.amichne.kast.api.contract.skill.KastImplementationsQuery
import io.github.amichne.kast.api.contract.skill.WrapperCallDirection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ObservedAnalysisBackendTest {
    @Test
    fun `selector handle authority remains available through observation`() {
        val selectorHandles = DigestSelectorHandleAuthority(
            workspaceRoot = "/workspace",
            backendName = "idea",
            backendVersion = "test",
            backendInstanceId = "observed-backend-test",
            semanticGeneration = { 0L },
        )
        val page = RelationTraversalPageInfo.create(
            cardinality = ResultCardinality.Exact(0),
            returnedCount = 0,
            returnedBefore = 0,
            visitedCandidateCount = 0,
            candidateVisitLimit = 16_384,
            nextHandle = null,
        )
        val delegate = RecordingRelationshipBackend(
            calls = CallRelationsResult(emptyList(), page),
            implementations = ImplementationRelationsResult(emptyList(), page),
            hierarchy = HierarchyRelationsResult(emptyList(), page),
            selectorHandles = selectorHandles,
        )
        val parentDisposable = com.intellij.openapi.util.Disposer.newDisposable()
        val unusedProject = com.intellij.mock.MockProject(null, parentDisposable)

        try {
            val observed = ObservedAnalysisBackend(delegate, KastDiagnosticsService(unusedProject))

            assertSame(selectorHandles, observed.selectorHandles)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(parentDisposable)
        }
    }

    @Test
    fun `typed relationship methods delegate exactly once`() = runBlocking {
        val page = RelationTraversalPageInfo.create(
            cardinality = ResultCardinality.Exact(0),
            returnedCount = 0,
            returnedBefore = 0,
            visitedCandidateCount = 0,
            candidateVisitLimit = 16_384,
            nextHandle = null,
        )
        val expectedCalls = CallRelationsResult(emptyList(), page)
        val expectedImplementations = ImplementationRelationsResult(emptyList(), page)
        val expectedHierarchy = HierarchyRelationsResult(emptyList(), page)
        val delegate = RecordingRelationshipBackend(
            calls = expectedCalls,
            implementations = expectedImplementations,
            hierarchy = expectedHierarchy,
        )
        val parentDisposable = com.intellij.openapi.util.Disposer.newDisposable()
        val unusedProject = com.intellij.mock.MockProject(null, parentDisposable)

        try {
            val observed = ObservedAnalysisBackend(delegate, KastDiagnosticsService(unusedProject))
            val selector = KastExactSymbolSelector(
                fqName = "sample.Subject",
                declarationFile = "/workspace/Subject.kt",
                declarationStartOffset = 0,
                kind = SymbolKind.CLASS,
            )

            assertSame(
                expectedCalls,
                observed.callRelations(
                    KastCallersQuery(
                        workspaceRoot = "/workspace",
                        selector = selector,
                        direction = WrapperCallDirection.INCOMING,
                        depth = 1,
                        maxResults = 4,
                    ),
                ),
            )
            assertSame(
                expectedImplementations,
                observed.implementationRelations(
                    KastImplementationsQuery(
                        workspaceRoot = "/workspace",
                        selector = selector,
                        maxResults = 4,
                    ),
                ),
            )
            assertSame(
                expectedHierarchy,
                observed.hierarchyRelations(
                    KastHierarchyQuery(
                        workspaceRoot = "/workspace",
                        selector = selector,
                        direction = TypeHierarchyDirection.BOTH,
                        depth = 1,
                        maxResults = 4,
                    ),
                ),
            )

            assertEquals(1, delegate.callRelationsCount)
            assertEquals(1, delegate.implementationRelationsCount)
            assertEquals(1, delegate.hierarchyRelationsCount)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(parentDisposable)
        }
    }
}

private class RecordingRelationshipBackend(
    private val calls: CallRelationsResult,
    private val implementations: ImplementationRelationsResult,
    private val hierarchy: HierarchyRelationsResult,
    override val selectorHandles: SelectorHandleAuthority = SelectorHandleAuthority.Unsupported,
) : CloseableAnalysisBackend {
    var callRelationsCount: Int = 0
        private set
    var implementationRelationsCount: Int = 0
        private set
    var hierarchyRelationsCount: Int = 0
        private set

    override suspend fun callRelations(query: KastCallersQuery): CallRelationsResult {
        callRelationsCount += 1
        return calls
    }

    override suspend fun implementationRelations(
        query: KastImplementationsQuery,
    ): ImplementationRelationsResult {
        implementationRelationsCount += 1
        return implementations
    }

    override suspend fun hierarchyRelations(query: KastHierarchyQuery): HierarchyRelationsResult {
        hierarchyRelationsCount += 1
        return hierarchy
    }

    override suspend fun capabilities(): io.github.amichne.kast.api.contract.BackendCapabilities =
        unexpected("capabilities")

    override suspend fun resolveSymbol(
        query: io.github.amichne.kast.api.validation.ParsedSymbolQuery,
    ): io.github.amichne.kast.api.contract.result.SymbolResult = unexpected("resolveSymbol")

    override suspend fun findReferences(
        query: io.github.amichne.kast.api.validation.ParsedReferencesQuery,
    ): io.github.amichne.kast.api.contract.result.ReferencesResult = unexpected("findReferences")

    override suspend fun diagnostics(
        query: io.github.amichne.kast.api.validation.ParsedDiagnosticsQuery,
    ): io.github.amichne.kast.api.contract.result.DiagnosticsResult = unexpected("diagnostics")

    override suspend fun rename(
        query: io.github.amichne.kast.api.validation.ParsedRenameQuery,
    ): io.github.amichne.kast.api.contract.result.RenameResult = unexpected("rename")

    override suspend fun applyEdits(
        query: io.github.amichne.kast.api.validation.ParsedApplyEditsQuery,
    ): io.github.amichne.kast.api.contract.result.ApplyEditsResult = unexpected("applyEdits")

    override fun close(): Unit = unexpected("close")

    private fun unexpected(operation: String): Nothing = error("Unexpected delegate invocation: $operation")
}
