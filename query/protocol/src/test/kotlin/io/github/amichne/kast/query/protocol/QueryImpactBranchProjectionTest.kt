package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ImpactBranchCompletionDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationHistoryDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactTryBranchAlternativeDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryImpactWitnessSection
import io.github.amichne.kast.query.contract.QueryImpactWitnessView
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.relation.contract.ValueTryBranchAlternative
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryImpactBranchProjectionTest {
    @Test
    fun `normal branch proof survives path representation history`() {
        val b = BranchFixture()
        with(b) {
            val path =
                QueryImpactPath.fromEvidence(
                        f.producer,
                        listOf(QueryImpactStep.Compiler(edge)),
                        QueryImpactRepresentation.Present(f.origin.transfer(edge).refined()),
                        terminal,
                    )
                    .refined()
            val document = path.impactDocument().refined()
            val proof =
                (document.steps.values.single() as ImpactPathStepDocument.Compiler).transfer.evidence
                    as ImpactTransferEvidenceDocument.NormalBranchResult
            assertEquals(20, proof.tryRange.start.value)
            assertEquals(80, proof.tryRange.end.value)
            assertEquals(25, proof.branchRange.start.value)
            assertEquals(45, proof.branchRange.end.value)
            assertEquals(ImpactTryBranchAlternativeDocument.TryBody, proof.alternative)
            assertEquals(ImpactBranchCompletionDocument.NORMAL_COMPLETION, proof.condition)
            val history =
                (document.representation as ImpactRepresentationEvidenceDocument.Present)
                    .branches
                    .values
                    .single()
                    .history
                    .values
                    .last() as ImpactRepresentationHistoryDocument.CompilerTransfer
            assertEquals(proof, history.transfer.evidence)
        }
    }

    @Test
    fun `normal branch proof survives a single record witness page and replay`() {
        val b = BranchFixture()
        with(b) {
            val proof =
                ImpactTransferEvidenceDocument.NormalBranchResult(
                    ImpactSourceRangeDocument(ProtocolOffset.parse(20).refined(), ProtocolOffset.parse(80).refined()),
                    ImpactSourceRangeDocument(ProtocolOffset.parse(25).refined(), ProtocolOffset.parse(45).refined()),
                    ImpactTryBranchAlternativeDocument.TryBody,
                    ImpactBranchCompletionDocument.NORMAL_COMPLETION,
                )
            val plainPath =
                QueryImpactPath.fromEvidence(
                        f.producer,
                        listOf(QueryImpactStep.Compiler(edge)),
                        QueryImpactRepresentation.NotModeled,
                        terminal,
                    )
                    .refined()
            val ledger =
                QueryImpactLedger.fromEvidence(
                        listOf(f.producer),
                        domain.boundary,
                        QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                        emptyList(),
                        emptyList(),
                        listOf(first, end),
                        listOf(plainPath),
                        originalProducers = listOf(QueryImpactProducer.admit(f.producer, f.call).refined()),
                    )
                    .refined()
            val view = QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.NATIVE_READS, 1, 2).refined()
            val page = QueryRows.ImpactWitness.of(view).projectWitnessItems() as QueryProjection.Projected
            val witness =
                (page.values.single() as QueryResultItemDocument.ImpactWitness).item.witness
                    as ImpactWitnessDocument.CompilerTransfer
            assertEquals(proof, witness.transfer.evidence)
            assertEquals(page, QueryRows.ImpactWitness.of(view).projectWitnessItems())
        }
    }

    private inner class BranchFixture {
        val f = ImpactPathProjectionFixture()
        val target = f.site(20, 80, ValueRole.ExpressionResult)
        val branch = ExactDeclarationTextRange.parse(25, 45).refined()
        val evidence =
            ValueTransferEvidence.NormalBranchResult.fromCompiler(
                    target.range,
                    branch,
                    ValueTryBranchAlternative.TryBody,
                )
                .refined()
        val edge =
            ValueTransfer.fromCompiler(f.producer, target, ValueTransferKind.BRANCH_ALTERNATIVE, evidence).refined()
        val owner = f.owner as RelationEndpoint.Resolved
        val domain =
            RelationRequest.start(
                SymbolSelector.issue(owner.lease, owner.scope, owner.evidence),
                RelationMeaning.References,
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(20).refined(),
                        WorkUnitLimit.parse(100).refined(),
                        ElapsedTimeLimitMillis.parse(1000).refined(),
                    ),
                    RelationByteLimit.parse(100000).refined(),
                ),
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )

        private fun observation(source: ValueSite, transfers: List<ValueTransfer>) =
            ValueFlowStep.fromCompiler(
                    source,
                    transfers,
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    domain,
                    RelationWorkCount.parse(1).refined(),
                )
                .refined()

        val first = observation(f.producer, listOf(edge))
        val end = observation(target, emptyList())
        val terminal = QueryImpactTerminal.SupportedDomainEnd.admit(end).refined()
    }

    private fun <S, F> Refinement<S, F>.refined(): S =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
