package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationScopeExclusion
import io.github.amichne.kast.relation.contract.RelationScopeExclusionReason
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Production accounting from detached compiler-evidence fixtures; native model membership is separately qualified. */
class RelationScopeExclusionTest {
    private val fixture = RelationReadTest()

    @Test
    fun `a proven domain exit remains in a complete relation page without an unsupported obligation`() {
        val request = scopedRequest()
        val exclusion = exclusion(request)
        val collector = IntellijRelationCollector(request, { 0L })
        assertEquals(
            IntellijRelationProviderItemAdmission.READY,
            collector.beginProviderItem(fixture.providerItem("outside")),
        )
        assertTrue(collector.acceptScopeExclusion(exclusion))
        val complete =
            assertInstanceOf(
                RelationCompilation.Complete::class.java,
                collector.finish(IntellijRelationTermination.Terminal),
            )
        assertEquals(emptyList<RelationFact>(), complete.batch.facts)
        assertEquals(listOf(exclusion), complete.batch.scopeExclusions)
        assertEquals(emptyList<RelationOmissionEvidence>(), complete.batch.omissions)
        assertEquals(1L, complete.batch.examinedWorkUnits.value)
        assertEquals(
            exclusion.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong(),
            complete.batch.encodedBytes.value,
        )
        assertFalse(complete.batch.scopeExclusions.single().target.compilerIdentity.value.isBlank())
        assertThrows(UnsupportedOperationException::class.java) {
            (complete.batch.scopeExclusions as MutableList<RelationScopeExclusion>).clear()
        }
    }

    @Test
    fun `exclusion grant stops before the next refinement and resumes the original inventory`() {
        val request = scopedRequest(resultLimit = 1)
        val locators =
            listOf(71, 91).map { offset ->
                RelationProviderLocator.Callee.Reference(
                    request.subject.file,
                    (RelationOccurrence.fromBoundary(request.subject.file, offset, offset + 3) as Refinement.Refined)
                        .value
                        .range,
                    fixture.providerItem("outside:$offset"),
                )
            }
        var preparations = 0
        fun read(request: RelationRequest, collector: IntellijRelationCollector): RelationCompilation {
            val termination =
                readRelationInventory(
                    request,
                    collector,
                    prepare = {
                        preparations++
                        val inventory =
                            IntellijRelationInventory<RelationProviderLocator.Callee>(
                                collector,
                                io.github.amichne.kast.kernel.ReadLimits.Default,
                                io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation.None,
                            )
                        for (locator in locators) assertTrue(inventory.append(Refinement.Refined(locator)))
                        inventory.finish(true, RelationProviderState::callees)
                    },
                    confirm = { _, locator ->
                        collector.acceptScopeExclusion(exclusion(request, locator.range.startInclusive))
                    },
                    cancellationCheck = {},
                )
            return collector.finish(
                if (termination == ProviderTermination.TERMINAL) IntellijRelationTermination.Terminal
                else IntellijRelationTermination.Resumable(emptySet())
            )
        }
        val first =
            assertInstanceOf(
                RelationCompilation.Qualified::class.java,
                read(request, IntellijRelationCollector(request, { 0L })),
            )
        val continuation = (first.coverage as RelationIncompleteCoverage.Resumable).continuation
        assertEquals(listOf(71), first.batch.scopeExclusions.map { it.occurrence.range.startInclusive })
        assertEquals(1L, first.batch.examinedWorkUnits.value)
        val resumed =
            (RelationRequest.resume(
                    (request.subject as RelationEndpoint.Subject).selector,
                    request.meaning,
                    request.budget,
                    continuation,
                    request.boundary,
                ) as Refinement.Refined)
                .value
        val second =
            assertInstanceOf(
                RelationCompilation.Complete::class.java,
                read(resumed, IntellijRelationCollector(resumed, { 0L })),
            )
        assertEquals(listOf(91), second.batch.scopeExclusions.map { it.occurrence.range.startInclusive })
        assertEquals(1, preparations)
    }

    @Test
    fun `foreign expansion evidence cannot enter a page under another domain`() {
        val retained = scopedRequest()
        val expanded =
            RelationRequest.start(
                (retained.subject as RelationEndpoint.Subject).selector,
                retained.meaning,
                retained.budget,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        val exclusion = exclusion(expanded)
        val collector = IntellijRelationCollector(retained, { 0L })
        collector.beginProviderItem(fixture.providerItem("foreign"))
        assertFalse(collector.acceptScopeExclusion(exclusion))
        assertInstanceOf(
            RelationCompilation.Rejected::class.java,
            collector.finish(IntellijRelationTermination.Terminal),
        )
    }

    private fun scopedRequest(resultLimit: Int = 8): RelationRequest {
        val read = fixture.request(RelationMeaning.Callees, resultLimit = resultLimit)
        val file =
            (read.subject.file as io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.Workspace).path
        return RelationRequest.start(
            (read.subject as RelationEndpoint.Subject).selector,
            read.meaning,
            read.budget,
            RelationSearchBoundary.Explicit(
                io.github.amichne.kast.symbol.contract.SymbolSearchScope.ExactFile(
                    file,
                    read.searchScope.sourceKinds,
                    read.searchScope.generatedSources,
                )
            ),
        )
    }

    private fun exclusion(request: RelationRequest, offset: Int = 71): RelationScopeExclusion {
        val targetFile =
            (io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath.fromCanonicalPath(
                    request.subject.lease.workspaceRoot,
                    java.nio.file.Path.of("/workspace/other/Excluded.kt"),
                ) as Refinement.Refined)
                .value
        val signature =
            (io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature.function(
                    "other.Excluded.run",
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                ) as Refinement.Refined)
                .value
        val target =
            (io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromBoundary(
                    io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.Workspace(targetFile),
                    0,
                    10,
                    "run",
                    "other.Excluded.run",
                    io.github.amichne.kast.symbol.contract.CompilerSymbolKind.FUNCTION,
                    signature,
                ) as Refinement.Refined)
                .value
        return (RelationScopeExclusion.fromNativeBoundary(
                request,
                (RelationOccurrence.fromBoundary(request.subject.file, offset, offset + 3) as Refinement.Refined).value,
                target,
                RelationScopeExclusionReason.SOURCE_DOMAIN,
            ) as Refinement.Refined)
            .value
    }
}
