package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.relation.contract.ValueTryBranchAlternative
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class QueryImpactBranchLedgerTest {
    @Test
    fun `branch provenance and finally obligations survive ledger composition and cannot disappear from paths`() {
        val f = QueryImpactLedgerFixture()
        val target =
            ValueSite.fromCompiler(f.owner, ExactDeclarationTextRange.parse(5, 90).value(), ValueRole.ExpressionResult)
                .value()
        val proof =
            ValueTransferEvidence.NormalBranchResult.fromCompiler(
                    target.range,
                    ExactDeclarationTextRange.parse(8, 15).value(),
                    ValueTryBranchAlternative.TryBody,
                )
                .value()
        val edge = ValueTransfer.fromCompiler(f.producer, target, ValueTransferKind.BRANCH_ALTERNATIVE, proof).value()
        val obligation = ValueFlowObligation(f.producer, ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED)
        val start = f.observe(f.producer, listOf(edge), listOf(obligation))
        val end = f.observe(target, emptyList())
        val path =
            QueryImpactPath.fromEvidence(
                    f.producer,
                    listOf(QueryImpactStep.Compiler(edge)),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.SupportedDomainEnd.admit(end).value(),
                )
                .value()
        val qualified =
            QueryImpactPath.fromEvidence(
                    f.producer,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.Flow(obligation),
                )
                .value()
        fun ledger(paths: List<QueryImpactPath>) =
            QueryImpactLedger.fromEvidence(
                listOf(f.producer),
                f.domain.boundary,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                emptyList(),
                emptyList(),
                listOf(start, end),
                paths,
                originalProducers = listOf(f.producerWitness),
            )
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION), ledger(listOf(path)))
        val admitted = ledger(listOf(path, qualified)).value()
        assertEquals(2, admitted.paths.size)
        assertSame(proof, (path.steps.single() as QueryImpactStep.Compiler).transfer.evidence)
    }
}

private fun <T, F> Refinement<T, F>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
