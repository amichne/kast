package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Pure progress rule from explicit detached facts; no compiler or platform behavior is asserted. */
class ReferenceResumeContractTest {
    @Test
    fun `normalized definitions retain their original provider file with stable duplicate admission`() {
        val request = request()
        fun providerFile(name: String) =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(
                        request.subject.lease.workspaceRoot,
                        Path.of("/workspace/$name.java"),
                    )
                    .refined()
            )
        val normalizedFile = request.subject.file
        val first =
            RelationProviderLocator.Definition.Normalized(
                normalizedFile,
                ExactDeclarationTextRange.parse(1, 2).refined(),
                RelationProviderItemDescriptor.parse("definition:first").refined(),
                RelationProviderElementClass.parse("fixture.NormalizedDeclaration").refined(),
                providerFile("FirstProvider"),
            )
        val duplicate = first.copy(providerFile = providerFile("OtherProvider"))
        val second =
            first.copy(
                range = ExactDeclarationTextRange.parse(3, 4).refined(),
                descriptor = RelationProviderItemDescriptor.parse("definition:second").refined(),
                providerFile = providerFile("SecondProvider"),
            )
        val forward = RelationProviderState.definitions(listOf(first, duplicate, second))
        val reversed = RelationProviderState.definitions(listOf(second, duplicate, first))
        assertEquals(listOf(first, second), forward.prepared)
        assertEquals(forward.canonicalProjection(), reversed.canonicalProjection())
        val successor = forward.consume()
        assertEquals(listOf(second), successor.prepared)
        val restored = successor.prepared.single() as RelationProviderLocator.Definition.Normalized
        assertEquals(normalizedFile, restored.file)
        assertEquals(providerFile("SecondProvider"), restored.providerFile)
        assertEquals(Refinement.Refined(successor), successor.advanceFrom(forward))
    }

    @Test
    fun `a cursor cannot advertise a position unrelated to its retained inventory`() {
        val request = request()
        val state = RelationProviderState.references(locators(request))
        val unrelated = request.providerCursor.advance(RelationProviderItemDescriptor.parse("unrelated-work").refined())
        assertEquals(
            Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_STATE_CURSOR_MISMATCH),
            page(request, state, unrelated),
        )
        assertEquals(0L, state.consumedLocatorCount.value)
        assertEquals(1L, state.providerCursor.nextPosition.value)
        assertEquals(1L, state.consume().consumedLocatorCount.value)
        assertEquals(2L, state.consume().providerCursor.nextPosition.value)
    }

    @Test
    fun `generic cursor advance cannot manufacture resumability for exhausted reference inventory`() {
        val request = request()
        assertEquals(
            Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_STATE_EXHAUSTED),
            page(request, RelationProviderState.references(emptyList())),
        )
        val inventory = RelationProviderState.references(locators(request))
        val exhausted = inventory.consume().consume().consume()
        assertEquals(
            Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_STATE_EXHAUSTED),
            page(request, exhausted),
        )
    }

    @Test
    fun `successor must retain the exact immutable inventory proof and advance its ordinal`() {
        val request = request()
        val values = locators(request)
        val inventory = RelationProviderState.references(values)
        val consumed = inventory.consume()
        val first = page(request, consumed).refined()
        val continuation = (first.coverage as RelationIncompleteCoverage.Resumable).continuation
        val resumed =
            RelationRequest.resume(
                    (request.subject as RelationEndpoint.Subject).selector,
                    request.meaning,
                    request.budget,
                    continuation,
                )
                .refined()
        assertEquals(Refinement.Rejected(RelationIncompleteCoverageFailure.CURSOR_REWIND), page(resumed, inventory))
        assertEquals(
            Refinement.Rejected(RelationIncompleteCoverageFailure.CURSOR_NOT_ADVANCED),
            page(resumed, consumed),
        )
        val reconstructed = RelationProviderState.references(values).consume().consume()
        assertEquals(
            Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_INVENTORY_MISMATCH),
            page(resumed, reconstructed),
        )
        assertInstanceOf(
            RelationIncompleteCoverage.Resumable::class.java,
            page(resumed, consumed.consume()).refined().coverage,
        )
        assertEquals(
            Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_STATE_EXHAUSTED),
            page(resumed, consumed.consume().consume()),
        )
    }

    private fun page(
        request: RelationRequest,
        state: RelationProviderState,
        cursor: RelationProviderCursor = state.providerCursor,
    ): Refinement<RelationCompilation.Qualified, RelationIncompleteCoverageFailure> {
        val batch =
            RelationBatch.create(
                    request,
                    emptyList(),
                    RelationByteCount.parse(0).refined(),
                    RelationWorkCount.parse(0).refined(),
                    RelationResultCount.parse(0).refined(),
                )
                .refined()
        return RelationCompilation.qualifiedResumable(
            batch,
            setOf(RelationLimitation.WORK_LIMIT_REACHED),
            cursor,
            state,
        )
    }

    private fun locators(request: RelationRequest) =
        (0 until 3).map { ordinal ->
            RelationProviderLocator.Reference(
                request.subject.file,
                ExactDeclarationTextRange.parse(ordinal, ordinal + 1).refined(),
                RelationProviderItemDescriptor.parse("reference:$ordinal").refined(),
            )
        }

    private fun request(): RelationRequest {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(19).refined())
        val file =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/workspace/Subject.kt")).refined()
            )
        val scope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    0,
                    10,
                    "run",
                    "sample.run",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function("sample.run", null, emptyList(), emptyList(), 0).refined(),
                )
                .refined()
        return RelationRequest.start(
            SymbolSelector.issue(lease, scope, evidence),
            RelationMeaning.References,
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(3).refined(),
                    ElapsedTimeLimitMillis.parse(1_000).refined(),
                ),
                RelationByteLimit.parse(10_000).refined(),
            ),
        )
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refinement, got $failure")
        }
}
