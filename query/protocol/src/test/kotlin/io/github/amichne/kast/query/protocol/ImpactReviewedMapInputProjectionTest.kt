package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingRepresentationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueSemanticsDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.admitsEvidence
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryImpactWitnessEntry
import io.github.amichne.kast.query.contract.QueryImpactWitnessSection
import io.github.amichne.kast.query.contract.QueryImpactWitnessView
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedMapInputProjectionTest {
    @Test
    fun `input projection keeps canonical owner authority separate from applied content relationship`() {
        val f = InputProjectionFixture()
        val document = f.input.impactDocument().inputProjection()
        assertTrue(document.admitsEvidence())
        assertEquals(
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER,
            (document.binding.relation
                    as
                    io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument.CallbackCorrespondence)
                .semantics,
        )
        val owner = document.target.executionOwner as ImpactExecutionOwnerDocument.ReviewedCallback
        assertEquals(owner.binding, document.binding.authority)
        assertEquals(
            owner.binding.body,
            (document.binding.authority as io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument)
                .body,
        )
        assertEquals(owner.binding.artifacts, document.binding.authority.artifacts)
    }

    @Test
    fun `compact finding retains both input and output relationships of the same callback`() {
        val f = InputProjectionFixture()
        val end = f.base.observation(f.output.target, emptyList())
        val path =
            QueryImpactPath.fromEvidence(
                    f.input.source,
                    listOf(
                        QueryImpactStep.ReviewedLibrary(f.input),
                        QueryImpactStep.Compiler(f.compiler),
                        QueryImpactStep.ReviewedLibrary(f.output),
                    ),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.SupportedDomainEnd.admit(end).inputProjection(),
                )
                .inputProjection()
        val observations = f.observations(end)
        val ledger =
            QueryImpactLedger.fromEvidence(
                    listOf(f.input.source),
                    f.base.domain.boundary,
                    QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                    emptyList(),
                    emptyList(),
                    observations,
                    listOf(path),
                )
                .inputProjection()
        val view = QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.FINDINGS, 0, 1).inputProjection()
        val finding = (view.entries.single().evidence as QueryImpactWitnessEntry.Finding).finding
        val projected =
            finding
                .findingDocument(
                    QueryResultRowReference.parse("result-row:v1:00000000-0000-0000-0000-000000000001")
                        .inputProjection()
                )
                .inputProjection()
        assertEquals(
            listOf(
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER,
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT,
            ),
            projected.reviewedRelations.values.map {
                (it.relation as ImpactReviewedValueRelationDocument.CallbackCorrespondence).semantics
            },
        )
        assertEquals(ImpactFindingRepresentationDocument.NotModeled, projected.representation)
    }
}

private class InputProjectionFixture {
    val base = ReviewedProjectionFixture()
    private val binding = base.binding
    private val owner = base.owner
    private val target = RelationEndpoint.resolve(owner.lease, owner.scope, binding.selected.target).inputProjection()
    private val call = ValueInvocation.fromCompiler(owner, binding.occurrence.range, target).inputProjection()
    private val formal =
        ValueFormalParameter.fromCompiler(
                owner,
                ValueArgumentPosition.parse(0).inputProjection(),
                SymbolDiscoveryCandidateName.parse("item").inputProjection(),
                projectionRange(41, 45),
                ValueExecutionOwner.reviewedCallback(binding),
            )
            .inputProjection()
    private val source =
        ValueSite.fromCompiler(owner, projectionRange(30, 35), ValueRole.Receiver(call, ValueReceiverKind.EXTENSION))
            .inputProjection()
    val input = ReviewedValueTransfer.callbackInput(source, binding, formal).inputProjection()
    private val returned =
        ValueSite.fromCompiler(owner, projectionRange(50, 60), ValueRole.Return, formal.executionOwner)
            .inputProjection()
    val compiler = ValueTransfer.fromCompiler(input.target, returned, ValueTransferKind.RETURN).inputProjection()
    val output = ReviewedValueTransfer.callbackResult(returned, binding).inputProjection()

    fun observations(end: ValueFlowStep): List<ValueFlowStep> =
        listOf(
            base.observation(input.source, listOf(input)),
            compilerObservation(),
            base.observation(output.source, listOf(output)),
            end,
        )

    private fun compilerObservation() =
        io.github.amichne.kast.relation.contract.ValueFlowStep.fromEvidence(
                input.target,
                listOf(compiler),
                emptyList(),
                io.github.amichne.kast.relation.contract.ValueFlowTerminal.SupportedDomainExhausted,
                base.domain,
                io.github.amichne.kast.relation.contract.RelationWorkCount.parse(1).inputProjection(),
                reviewedTransfers = emptyList(),
            )
            .inputProjection()
}

private fun projectionRange(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).inputProjection()

private fun <V, F> Refinement<V, F>.inputProjection(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid map input projection fixture: $failure")
    }
