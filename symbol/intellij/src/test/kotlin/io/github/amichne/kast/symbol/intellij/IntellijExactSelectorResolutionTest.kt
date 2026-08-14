package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.ExactDeclarationEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationSelector
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class IntellijExactSelectorResolutionTest {
    @Test
    fun `same name collisions round trip only through their exact native declaration`() {
        val batch = batch(7, 41)
        val first = SymbolDiscoverySelection.select(batch, 0).refined()
        val second = SymbolDiscoverySelection.select(batch, 1).refined()
        val query = query(
            lookup = { _, key ->
                found(
                    start = key.offset.value,
                    end = key.offset.value + 10,
                    qualifiedName = "sample.Owner" + key.offset.value + ".service",
                )
            },
        )

        val firstSelector = query.resolve(compiled(first), first).selector()
        val secondSelector = query.resolve(compiled(second), second).selector()
        assertNotEquals(firstSelector.fingerprint, secondSelector.fingerprint)

        val revalidated = query.revalidate(compiled(firstSelector), firstSelector)
            as IntellijExactSelectorRevalidation.Revalidated
        assertSame(firstSelector, revalidated.proof.selector)
    }

    @Test
    fun `stale ambiguous unsupported and out of scope lookups remain distinct closed failures`() {
        val selection = SymbolDiscoverySelection.select(batch(7), 0).refined()
        IntellijExactDeclarationLookupRejection.entries.forEach { nativeReason ->
            val result = query(
                lookup = { _, _ ->
                    IntellijExactDeclarationLookupResult.Rejected(nativeReason)
                },
            ).resolve(compiled(selection), selection)

            assertEquals(
                nativeReason.toPublicRejection(),
                (result as IntellijExactSelectorResolution.Rejected).reason,
            )
        }

        val mismatchedEvidence = query(
            lookup = { _, _ -> found(start = 8, end = 18) },
        )
            .resolve(compiled(selection), selection)
        assertEquals(
            IntellijExactSelectorRejection.NATIVE_EVIDENCE_MISMATCH,
            (mismatchedEvidence as IntellijExactSelectorResolution.Rejected).reason,
        )
    }

    @Test
    fun `changed declaration evidence invalidates an issued selector`() {
        val selection = SymbolDiscoverySelection.select(batch(7), 0).refined()
        val selector = query(
            lookup = { _, _ -> found(start = 7, end = 17) },
        )
            .resolve(compiled(selection), selection)
            .selector()
        val moved = query(
            lookup = { _, _ -> found(start = 7, end = 18) },
        )
            .revalidate(compiled(selector), selector)

        assertEquals(
            IntellijExactSelectorRejection.DECLARATION_MOVED_OR_CHANGED,
            (moved as IntellijExactSelectorRevalidation.Rejected).reason,
        )
    }

    @Test
    fun `dumb transitions native failures and cancellation never produce selectors`() {
        val selection = SymbolDiscoverySelection.select(batch(7), 0).refined()
        val initialDumb = query(
            lookup = { _, _ -> found(start = 7, end = 17) },
            environmentState = { IntellijDiscoveryEnvironmentState.DUMB },
        ).resolve(compiled(selection), selection)
        assertEquals(
            IntellijExactSelectorRejection.DUMB_MODE,
            (initialDumb as IntellijExactSelectorResolution.Rejected).reason,
        )

        var observations = 0
        val transition = query(
            lookup = { _, _ -> found(start = 7, end = 17) },
            environmentState = {
                observations += 1
                if (observations == 1) {
                    IntellijDiscoveryEnvironmentState.READY
                } else {
                    IntellijDiscoveryEnvironmentState.DUMB
                }
            },
        ).resolve(compiled(selection), selection)
        assertEquals(
            IntellijExactSelectorRejection.DUMB_MODE,
            (transition as IntellijExactSelectorResolution.Rejected).reason,
        )

        val nativeFailure = query(
            lookup = { _, _ -> error("native failure") },
        )
            .resolve(compiled(selection), selection)
        assertEquals(
            IntellijExactSelectorRejection.NATIVE_FAILURE,
            (nativeFailure as IntellijExactSelectorResolution.Rejected).reason,
        )
        assertThrows<ProcessCanceledException> {
            query(
                lookup = { _, _ -> found(start = 7, end = 17) },
                cancellationCheck = { throw ProcessCanceledException() },
            ).resolve(compiled(selection), selection)
        }
    }

    @Test
    fun `moved leases and rejected scopes stop before exact native resolution`() {
        val current = lease()
        val moved = SemanticReadLease(
            current.workspaceRoot,
            EvidenceGeneration.parse(current.generation.value + 1).refined(),
        )
        assertEquals(
            IntellijExactSelectorRejection.GENERATION_MOVED,
            (
                admitExactSelectorLease(current, moved)
                    as IntellijExactSelectorLeaseAdmission.Rejected
            ).reason,
        )
        val otherRoot = SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/other")).refined(),
            current.generation,
        )
        assertEquals(
            IntellijExactSelectorRejection.WORKSPACE_ROOT_MISMATCH,
            (
                admitExactSelectorLease(current, otherRoot)
                    as IntellijExactSelectorLeaseAdmission.Rejected
            ).reason,
        )

        val failure = IntellijSearchScopeFailure.TargetProvenanceUnknown
        val scopeRejected = exactSelectorResolutionFromScoped(
            IntellijScopedQueryResult.Rejected(setOf(failure)),
        )
        assertEquals(
            setOf(failure),
            (scopeRejected as IntellijExactSelectorResolution.ScopeRejected).failures,
        )
    }

    private fun query(
        lookup: IntellijExactDeclarationLookup,
        environmentState: () -> IntellijDiscoveryEnvironmentState = {
            IntellijDiscoveryEnvironmentState.READY
        },
        cancellationCheck: () -> Unit = {},
    ): IntellijExactSelectorQuery = IntellijExactSelectorQuery(
        lookup = lookup,
        environmentState = environmentState,
        cancellationCheck = cancellationCheck,
    )

    private fun found(
        start: Int,
        end: Int,
        qualifiedName: String? = "sample.Service.service",
    ): IntellijExactDeclarationLookupResult =
        IntellijExactDeclarationLookupResult.Found(
            ExactDeclarationEvidence.fromBoundary(
                file = fileIdentity(),
                rawStartInclusive = start,
                rawEndExclusive = end,
                rawName = "service",
                rawQualifiedIdentity = qualifiedName,
                rawRuntimeType = "sample.FakeDeclaration",
            ).refined(),
        )

    private fun compiled(
        selection: SymbolDiscoverySelection,
    ): CompiledIntellijSearchScope =
        compiled(selection.lease, selection.scope)

    private fun compiled(
        selector: ExactDeclarationSelector,
    ): CompiledIntellijSearchScope =
        compiled(selector.lease, selector.scope)

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

    private fun batch(
        vararg offsets: Int,
    ): SymbolDiscoveryBatch {
        val candidates = offsets.map { offset ->
            SymbolDiscoveryCandidate.fromBoundary(
                kind = SymbolDiscoveryKind.SYMBOL,
                rawName = "service",
                lease = lease(),
                nativePath = Path.of("/workspace/src/Service.kt"),
                virtualFileUrl = "file:///workspace/src/Service.kt",
                rawOffset = offset,
            ).refined()
        }.sorted()
        val request = request(candidates.size)
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

    private fun request(
        resultLimit: Int,
    ): SymbolDiscoveryRequest = SymbolDiscoveryRequest(
        scope = SymbolSearchScopeRequest(
            lease = lease(),
            scope = SymbolSearchScope.Workspace(
                sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
                libraries = SymbolLibraryPolicy.EXCLUDE,
            ),
        ),
        kind = SymbolDiscoveryKind.SYMBOL,
        pattern = SymbolDiscoveryPattern.parse("service").refined(),
        budget = SymbolDiscoveryBudget(
            resources = ResourceBudget(
                resultLimit = ResultLimit.parse(resultLimit).refined(),
                workUnitLimit = WorkUnitLimit.parse(100L).refined(),
                elapsedTimeLimit = ElapsedTimeLimitMillis.parse(1_000L).refined(),
            ),
            returnedBytes = SymbolDiscoveryByteLimit.parse(10_000L).refined(),
        ),
    )

    private fun fileIdentity() =
        io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.fromBoundary(
            workspaceRoot = root(),
            nativePath = Path.of("/workspace/src/Service.kt"),
            virtualFileUrl = "file:///workspace/src/Service.kt",
        ).refined()

    private fun root(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun lease(): SemanticReadLease =
        SemanticReadLease(root(), EvidenceGeneration.parse(19L).refined())

    private fun IntellijExactSelectorResolution.selector(): ExactDeclarationSelector =
        (this as IntellijExactSelectorResolution.Resolved).selector

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
