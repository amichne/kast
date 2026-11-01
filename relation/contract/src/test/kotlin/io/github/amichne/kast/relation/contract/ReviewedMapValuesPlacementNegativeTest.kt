package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class ReviewedMapValuesPlacementNegativeTest {
    @Test
    fun `mapValues callback formal cannot substitute for its actual return`() {
        val f = ReviewedMapValuesPlacementFixture()
        val ignored = Any()
        val constant = Any()
        val oracle = mapOf("key" to ignored).mapValues { constant }
        assertNotSame(ignored, oracle.getValue("key"))
        val outcome = f.read { binding ->
            val owner = ValueExecutionOwner.reviewedCallback(binding)
            val formal =
                ValueFormalParameter.fromCompiler(
                        f.base.enclosing,
                        f.base.position(0),
                        SymbolDiscoveryCandidateName.parse("entry").flowRefined(),
                        flowRange(434, 435),
                        owner,
                    )
                    .flowRefined()
            ValueSite.fromCompiler(
                    f.base.enclosing,
                    formal.range,
                    ValueRole.Formal(ValueFormalPosition.Parameter(formal)),
                    owner,
                )
                .flowRefined()
        }
        assertForbidden(outcome, ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
    }

    @Test
    fun `discarded local result cannot substitute for mapValues return`() {
        val f = ReviewedMapValuesPlacementFixture()
        val outcome = f.read { binding ->
            f.base.source(role = ValueRole.ExpressionResult, owner = ValueExecutionOwner.reviewedCallback(binding))
        }
        assertForbidden(outcome, ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
    }

    @Test
    fun `sibling callback return cannot gain mapValues output value`() {
        val f = ReviewedMapValuesPlacementFixture()
        val outcome = f.read { f.base.source() }
        assertForbidden(outcome, ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
    }

    private fun assertForbidden(outcome: MapValuesPlacementOutcome, transferCause: ValueFlowUnsupportedCause) {
        when (outcome) {
            is MapValuesPlacementOutcome.AdmissionRejected ->
                assertEquals(ReviewedCallbackValueFailure.CALLABLE_MISMATCH, outcome.cause)
            is MapValuesPlacementOutcome.TransferRejected -> assertEquals(transferCause, outcome.cause)
            is MapValuesPlacementOutcome.Observed ->
                throw AssertionError(
                    "Forbidden mapValues edge: ${outcome.transfer.source.role} to ${outcome.transfer.target.valuePath}"
                )
        }
    }
}
