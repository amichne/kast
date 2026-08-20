package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.ExactDeclarationEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationSelector
import io.github.amichne.kast.symbol.contract.ExactRelationEndpoint
import io.github.amichne.kast.symbol.contract.NativeRelationBudget
import io.github.amichne.kast.symbol.contract.NativeRelationByteLimit
import io.github.amichne.kast.symbol.contract.NativeRelationFact
import io.github.amichne.kast.symbol.contract.NativeRelationFamily
import io.github.amichne.kast.symbol.contract.NativeRelationOccurrence
import io.github.amichne.kast.symbol.contract.NativeRelationOutcome
import io.github.amichne.kast.symbol.contract.NativeRelationRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class NativeSymbolDedupReviewRegressionTest {
    @Test
    fun `duplicate discovery event at the record limit preserves exact coverage`() {
        val request = discoveryRequest()
        val file = LightVirtualFile("ReviewItem.kt")
        val item = FakeItem("ReviewItem")
        val scope = acceptingScope()
        val query = IntellijNativeDiscoveryQuery(
            itemFile = { IntellijDiscoveryItemFileResult.Found(file) },
            projector = { candidateRequest, _, _ ->
                SymbolDiscoveryCandidate.fromBoundary(
                    kind = candidateRequest.kind,
                    rawName = item.candidateName,
                    lease = candidateRequest.scope.lease,
                    nativePath = Path.of("/workspace/src/ReviewItem.kt"),
                    virtualFileUrl = file.url,
                    rawOffset = 7,
                )
            },
            environmentState = { IntellijDiscoveryEnvironmentState.READY },
            cancellationCheck = {},
            clock = FixedDiscoveryClock,
        )

        val execution = query.discover(
            compiledScope = compiledScope(request, scope),
            request = request,
            contributors = listOf(DuplicateContributor(item)),
        ) as IntellijNativeDiscoveryExecution.Produced

        val complete = execution.outcome as SymbolDiscoveryOutcome.Complete
        assertEquals(1, complete.batch.candidates.size)
    }

    @Test
    fun `duplicate relation event at the record limit preserves exact cardinality`() {
        val request = relationRequest()
        val scope = acceptingScope()
        val compiledScope = CompiledIntellijSearchScope(
            lease = request.selector.lease,
            scope = request.selector.scope,
            sourceRoots = emptyList(),
            nativeScope = scope,
        )
        val event = FakeRelationEvent
        val query = IntellijNativeRelationQuery(
            search = IntellijNativeRelationSearch { _, _, consumer ->
                assertTrue(consumer(event))
                assertTrue(consumer(event))
                IntellijNativeRelationSearchResult.Terminal()
            },
            projector = IntellijRelationFactProjector { relationRequest, _ ->
                Refinement.Refined(relationFact(relationRequest))
            },
            environmentState = { IntellijDiscoveryEnvironmentState.READY },
            cancellationCheck = {},
            clock = FixedRelationClock,
        )

        val execution = query.read(compiledScope, request) as IntellijNativeRelationExecution.Produced
        val complete = execution.outcome as NativeRelationOutcome.Complete
        assertEquals(1, complete.exactCount.value)
    }

    private fun discoveryRequest(): SymbolDiscoveryRequest = SymbolDiscoveryRequest(
        scope = SymbolSearchScopeRequest(lease(), workspaceScope()),
        kind = SymbolNameDiscoveryKind.SYMBOL,
        pattern = SymbolDiscoveryPattern.parse("ReviewItem").refined(),
        budget = SymbolDiscoveryBudget(
            resources = ResourceBudget(
                resultLimit = ResultLimit.parse(1).refined(),
                workUnitLimit = WorkUnitLimit.parse(20L).refined(),
                elapsedTimeLimit = ElapsedTimeLimitMillis.parse(1_000L).refined(),
            ),
            returnedBytes = SymbolDiscoveryByteLimit.parse(10_000L).refined(),
        ),
    )

    private fun relationRequest(): NativeRelationRequest = NativeRelationRequest(
        selector = selector(),
        family = NativeRelationFamily.REFERENCES,
        budget = NativeRelationBudget(
            resources = ResourceBudget(
                resultLimit = ResultLimit.parse(1).refined(),
                workUnitLimit = WorkUnitLimit.parse(20L).refined(),
                elapsedTimeLimit = ElapsedTimeLimitMillis.parse(1_000L).refined(),
            ),
            returnedBytes = NativeRelationByteLimit.parse(10_000L).refined(),
        ),
    )

    private fun selector(): ExactDeclarationSelector {
        val request = discoveryRequest()
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            kind = SymbolDiscoveryKind.SYMBOL,
            rawName = "subject",
            lease = request.scope.lease,
            nativePath = Path.of("/workspace/src/Subject.kt"),
            virtualFileUrl = "file:///workspace/src/Subject.kt",
            rawOffset = 5,
        ).refined()
        val batch = SymbolDiscoveryBatch.create(
            request = request,
            candidates = listOf(candidate),
            encodedBytes = candidate.projectedUtf8Size(),
            examinedWorkUnits = SymbolDiscoveryWorkCount.parse(1L).refined(),
            timings = SymbolDiscoveryTimings(
                nativeQuery = SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                projection = SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            ),
        ).refined()
        return ExactDeclarationSelector.issue(
            SymbolDiscoverySelection.select(batch, 0).refined(),
            evidence("/workspace/src/Subject.kt", "subject", 5),
        ).refined()
    }

    private fun relationFact(request: NativeRelationRequest): NativeRelationFact {
        val related = evidence("/workspace/src/Related.kt", "related", 11)
        return NativeRelationFact.create(
            subject = request.selector,
            family = request.family,
            related = ExactRelationEndpoint.bind(request.selector, related),
            occurrence = NativeRelationOccurrence.fromBoundary(
                file = fileIdentity("/workspace/src/Usage.kt"),
                rawStartInclusive = 20,
                rawEndExclusive = 27,
            ).refined(),
        ).refined()
    }

    private fun evidence(
        path: String,
        name: String,
        start: Int,
    ): ExactDeclarationEvidence =
        ExactDeclarationEvidence.fromBoundary(
            file = fileIdentity(path),
            rawStartInclusive = start,
            rawEndExclusive = start + name.length,
            rawName = name,
            rawQualifiedIdentity = "review.$name",
            rawRuntimeType = "review.Declaration",
        ).refined()

    private fun fileIdentity(path: String) =
        io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.fromBoundary(
            workspaceRoot(),
            Path.of(path),
            "file://$path",
        ).refined()

    private fun compiledScope(
        request: SymbolDiscoveryRequest,
        scope: GlobalSearchScope,
    ) = CompiledIntellijSearchScope(
        lease = request.scope.lease,
        scope = request.scope.scope,
        sourceRoots = emptyList(),
        nativeScope = scope,
    )

    private fun acceptingScope(): GlobalSearchScope = object : GlobalSearchScope() {
        override fun contains(file: VirtualFile): Boolean = true

        override fun isSearchInModuleContent(aModule: Module): Boolean = true

        override fun isSearchInLibraries(): Boolean = false
    }

    private fun workspaceScope() = SymbolSearchScope.Workspace(
        sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
        libraries = SymbolLibraryPolicy.EXCLUDE,
    )

    private fun lease() = SemanticReadLease(
        workspaceRoot = workspaceRoot(),
        generation = EvidenceGeneration.parse(31L).refined(),
    )

    private fun workspaceRoot() =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private class DuplicateContributor(
        private val item: FakeItem,
    ) : ChooseByNameContributorEx {
        override fun processNames(
            processor: Processor<in String>,
            scope: GlobalSearchScope,
            filter: IdFilter?,
        ) {
            processor.process(item.candidateName)
        }

        override fun processElementsWithName(
            name: String,
            processor: Processor<in NavigationItem>,
            parameters: FindSymbolParameters,
        ) {
            processor.process(item)
            processor.process(item)
        }
    }

    private data class FakeItem(val candidateName: String) : NavigationItem {
        override fun getName(): String = candidateName

        override fun getPresentation(): ItemPresentation? = null
    }

    private data object FakeRelationEvent : IntellijNativeRelationEvent

    private data object FixedDiscoveryClock : IntellijDiscoveryNanoClock {
        override fun now(): Long = 1L
    }

    private data object FixedRelationClock : IntellijRelationNanoClock {
        override fun now(): Long = 1L
    }

    private fun <T, E> Refinement<T, E>.refined(): T = (this as Refinement.Refined<T>).value
}
