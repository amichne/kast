package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ImpactActualExecutionDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingRepresentationDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationCountKnowledgeDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryImpactWitnessSection
import io.github.amichne.kast.query.contract.QueryImpactWitnessView
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.DependencyArtifactDigest
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedIterableMapArtifact
import io.github.amichne.kast.relation.contract.ReviewedIterableMapCallable
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedFindingProjectionTest {
    @Test
    fun `model free compact finding keeps reviewed evidence from the retained path`() {
        val fixture = ReviewedProjectionFixture()
        val path =
            QueryImpactPath.fromEvidence(
                    fixture.transfer.source,
                    listOf(QueryImpactStep.ReviewedLibrary(fixture.transfer)),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.SupportedDomainEnd.admit(fixture.end).reviewed(),
                )
                .reviewed()
        val ledger =
            QueryImpactLedger.fromEvidence(
                    listOf(fixture.transfer.source),
                    fixture.domain.boundary,
                    QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                    emptyList(),
                    emptyList(),
                    listOf(fixture.start, fixture.end),
                    listOf(path),
                )
                .reviewed()
        val view = QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.FINDINGS, 0, 1).reviewed()
        val row = QueryResultRowReference.parse("result-row:v1:00000000-0000-0000-0000-000000000001").reviewed()
        val page =
            QueryRows.ImpactWitness.of(view).projectWitnessItems(originalRowIds = listOf(row))
                as QueryProjection.Projected
        val finding =
            ((page.values.single() as QueryResultItemDocument.ImpactWitness).item.witness
                    as ImpactWitnessDocument.Finding)
                .finding
        assertEquals(ImpactFindingRepresentationDocument.NotModeled, finding.representation)
        val binding = finding.reviewedRelations.values.single()
        assertEquals(fixture.binding.impactDocument().reviewed(), binding)
        assertEquals("kotlin.collections.map", binding.selected.qualifiedIdentity.value)
        assertEquals(0, binding.selected.formalIndex.value)
        assertEquals(
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT,
            binding.semantics,
        )
        assertEquals(ImpactActualExecutionDocument.NOT_ESTABLISHED, binding.actualExecution)
        assertEquals(ImpactInvocationCountKnowledgeDocument.NOT_ESTABLISHED, binding.invocationCount)
        assertEquals(1744, binding.artifacts.mapLines.first.value)
        assertEquals(1823, binding.artifacts.mapToLines.last.value)
        assertTrue(ledger.representationModels.isEmpty())
        assertTrue(ledger.boundaryModels.isEmpty())
    }
}

private class ReviewedProjectionFixture {
    private val owner = ImpactPathProjectionFixture().owner as RelationEndpoint.Resolved
    private val bodyRange = range(40, 90)
    private val signature =
        CanonicalCompilerSignature.function(
                RelationCallableBody.Anonymous.sourceIdentity(owner.file, bodyRange),
                null,
                emptyList(),
                listOf("String"),
                0,
            )
            .reviewed() as CanonicalCompilerSignature.Function
    private val body = RelationCallableBody.Anonymous.fromCompiler(owner.file, bodyRange, signature).reviewed()
    val binding =
        ReviewedCallbackValueBinding.admit(
                owner,
                body,
                RelationOccurrence.fromBoundary(owner.file, 30, 100).reviewed(),
                RelationCallableBody.Named.fromCompiler(owner.evidence).reviewed(),
                selected(),
                ReviewedIterableMapArtifact.admit(
                        DependencyArtifactDigest.parse(
                                "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3"
                            )
                            .reviewed(),
                        DependencyArtifactDigest.parse(
                                "1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2"
                            )
                            .reviewed(),
                    )
                    .reviewed(),
            )
            .reviewed()
    private val source =
        ValueSite.fromCompiler(
                owner,
                range(50, 60),
                ValueRole.Return,
                ValueExecutionOwner.reviewedCallback(binding),
            )
            .reviewed()
    val transfer = ReviewedValueTransfer.callbackResult(source, binding).reviewed()
    val domain =
        RelationRequest.start(
            owner,
            RelationMeaning.References,
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(10).reviewed(),
                    WorkUnitLimit.parse(100).reviewed(),
                    ElapsedTimeLimitMillis.parse(1000).reviewed(),
                ),
                RelationByteLimit.parse(1_000_000).reviewed(),
            ),
            RelationSearchBoundary.RETAINED_SUBJECT,
        )
    val start = observation(source, listOf(transfer))
    val end = observation(transfer.target, emptyList())

    private fun selected(): ReviewedIterableMapCallable {
        val file =
            SymbolDiscoveryFileIdentity.External(
                DetachedVirtualFileUrl.parse("jar:///library.jar!/kotlin/collections/CollectionsKt.class").reviewed()
            )
        val signature =
            CanonicalCompilerSignature.function(
                    "kotlin.collections.map",
                    "kotlin/collections/Iterable<T>",
                    emptyList(),
                    listOf("(T) -> R"),
                    2,
                )
                .reviewed()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    1000,
                    1100,
                    "map",
                    "kotlin.collections.map",
                    CompilerSymbolKind.FUNCTION,
                    signature,
                )
                .reviewed()
        return ReviewedIterableMapCallable.admit(evidence, ValueArgumentPosition.parse(0).reviewed()).reviewed()
    }

    private fun observation(site: ValueSite, transfers: List<ReviewedValueTransfer>): ValueFlowStep =
        ValueFlowStep.fromEvidence(
                site,
                emptyList(),
                emptyList(),
                ValueFlowTerminal.SupportedDomainExhausted,
                domain,
                RelationWorkCount.parse(1).reviewed(),
                reviewedTransfers = transfers,
            )
            .reviewed()

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).reviewed()
}

private fun <V, F> Refinement<V, F>.reviewed(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid reviewed projection fixture: $failure")
    }
