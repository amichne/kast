package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.nio.file.Path

class IntellijExactSelectorTest {
    @Test
    fun `compiler signatures keep same name overloads exact`() {
        val first = SymbolDiscoverySelection.select(batch(7, 41), 0).refined()
        val second = SymbolDiscoverySelection.select(batch(7, 41), 1).refined()

        val firstSelector = SymbolSelector.issue(
            first,
            evidence(first, "sample.Service.call(kotlin.Int)"),
        ).refined()
        val secondSelector = SymbolSelector.issue(
            second,
            evidence(second, "sample.Service.call(kotlin.String)"),
        ).refined()

        assertNotEquals(firstSelector.fingerprint, secondSelector.fingerprint)
        val firstSignature = SymbolDescription.from(firstSelector).signature as
            CanonicalCompilerSignature.Function
        val secondSignature = SymbolDescription.from(secondSelector).signature as
            CanonicalCompilerSignature.Function
        assertEquals(listOf("sample.Service.call(kotlin.Int)"), firstSignature.valueParameters.map { it.value })
        assertEquals(
            listOf("sample.Service.call(kotlin.String)"),
            secondSignature.valueParameters.map { it.value },
        )
        assertNotEquals(firstSelector.compilerIdentity, secondSelector.compilerIdentity)
    }

    @Test
    fun `root and generation drift are distinct closed selector rejections`() {
        val current = lease()
        val moved = SemanticReadLease(
            current.workspaceRoot,
            EvidenceGeneration.parse(current.generation.value + 1).refined(),
        )
        val otherRoot = SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/other")).refined(),
            current.generation,
        )

        assertEquals(
            IntellijSymbolSelectorRejection.GENERATION_MOVED,
            (admitSymbolSelectorLease(current, moved) as IntellijSymbolSelectorLeaseAdmission.Rejected)
                .reason,
        )
        assertEquals(
            IntellijSymbolSelectorRejection.WORKSPACE_ROOT_MISMATCH,
            (admitSymbolSelectorLease(current, otherRoot) as IntellijSymbolSelectorLeaseAdmission.Rejected)
                .reason,
        )
    }

    @Test
    fun `native query round trips identical compiler evidence and rejects changed identity`() {
        val selection = SymbolDiscoverySelection.select(batch(7), 0).refined()
        val evidence = evidence(selection, "sample.Service.call(kotlin.Int)")
        val query = query(IntellijCompilerSymbolLookupResult.Found(evidence))

        val selector = (
            query.resolve(compiled(selection.lease, selection.scope), selection)
                as IntellijSymbolSelectorResolution.Resolved
                       ).selector
        val described = query.describe(compiled(selector.lease, selector.scope), selector)
            as IntellijSymbolDescriptionResolution.Described

        assertSame(selector, described.description.selector)
        val changed = query(
            IntellijCompilerSymbolLookupResult.Found(
                evidence(selection, "sample.Service.call(kotlin.String)"),
            ),
        ).describe(compiled(selector.lease, selector.scope), selector)
        assertEquals(
            IntellijSymbolSelectorRejection.DECLARATION_MOVED_OR_CHANGED,
            (changed as IntellijSymbolDescriptionResolution.Rejected).reason,
        )
    }

    private fun query(
        result: IntellijCompilerSymbolLookupResult,
    ): IntellijSymbolSelectorQuery = IntellijSymbolSelectorQuery(
        lookup = IntellijCompilerSymbolLookup { _, _ -> result },
        environmentState = { IntellijDiscoveryEnvironmentState.READY },
        cancellationCheck = {},
    )

    private fun compiled(
        lease: SemanticReadLease,
        scope: SymbolSearchScope,
    ): CompiledIntellijSearchScope = CompiledIntellijSearchScope(
        lease = lease,
        scope = scope,
        sourceRoots = emptyList(),
        nativeScope = object : GlobalSearchScope() {
            override fun contains(file: VirtualFile): Boolean = true

            override fun isSearchInModuleContent(aModule: Module): Boolean = true

            override fun isSearchInLibraries(): Boolean = false
        },
    )

    private fun evidence(
        selection: SymbolDiscoverySelection,
        compilerIdentity: String,
    ): CompilerGroundedSymbolEvidence = CompilerGroundedSymbolEvidence.fromBoundary(
        file = selection.candidate.location.file,
        rawStartInclusive =
            (selection.candidate.location as io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation.Declaration)
                .offset.value,
        rawEndExclusive =
            (selection.candidate.location as io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation.Declaration)
                .offset.value + 10,
        rawName = selection.candidate.name.value,
        rawQualifiedIdentity = "sample.Service.call",
        kind = CompilerSymbolKind.FUNCTION,
        signature = CanonicalCompilerSignature.function(
            rawQualifiedIdentity = "sample.Service.call",
            rawReceiverType = null,
            rawContextReceiverTypes = emptyList(),
            rawValueParameterTypes = listOf(compilerIdentity),
            rawTypeParameterCount = 0,
        ).refined(),
    ).refined()

    private fun batch(vararg offsets: Int): SymbolDiscoveryBatch {
        val request = request(offsets.size)
        val candidates = offsets.map { offset ->
            SymbolDiscoveryCandidate.fromBoundary(
                kind = SymbolDiscoveryKind.SYMBOL,
                rawName = "call",
                lease = request.scope.lease,
                nativePath = Path.of("/workspace/src/Service.kt"),
                virtualFileUrl = "file:///workspace/src/Service.kt",
                rawOffset = offset,
            ).refined()
        }.sorted()
        return SymbolDiscoveryBatch.create(
            request = request,
            candidates = candidates,
            encodedBytes = SymbolDiscoveryByteCount.parse(
                candidates.sumOf { it.projectedUtf8Size().value },
            ).refined(),
            examinedWorkUnits = SymbolDiscoveryWorkCount.parse(candidates.size.toLong()).refined(),
            timings = SymbolDiscoveryTimings(
                nativeQuery = SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                projection = SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            ),
        ).refined()
    }

    private fun request(resultLimit: Int): SymbolDiscoveryRequest = SymbolDiscoveryRequest(
        scope = SymbolSearchScopeRequest(
            lease = lease(),
            scope = SymbolSearchScope.Workspace(
                sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
                libraries = SymbolLibraryPolicy.EXCLUDE,
            ),
        ),
        target = SymbolDiscoveryTarget.Name(
            kind = SymbolNameDiscoveryKind.SYMBOL,
            pattern = SymbolDiscoveryPattern.parse("call").refined(),
            match = SymbolDiscoveryMatch.FUZZY,
        ),
        budget = SymbolDiscoveryBudget(
            resources = ResourceBudget(
                resultLimit = ResultLimit.parse(resultLimit).refined(),
                workUnitLimit = WorkUnitLimit.parse(100L).refined(),
                elapsedTimeLimit = ElapsedTimeLimitMillis.parse(1_000L).refined(),
            ),
            returnedBytes = SymbolDiscoveryByteLimit.parse(10_000L).refined(),
        ),
    )

    private fun lease(): SemanticReadLease = SemanticReadLease(
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
        EvidenceGeneration.parse(19L).refined(),
    )

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
