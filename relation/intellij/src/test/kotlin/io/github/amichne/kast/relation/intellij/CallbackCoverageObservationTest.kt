package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackForwardingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CallbackCoverageObservationTest {
    @Test
    fun `formal incompleteness is observed before inherited supplier incompleteness`() {
        val observed = CoverageObservation()
        val formal = snapshot(CallbackInvocationScan.INCOMPLETE, CallbackInvocationFlowCause.PARAMETER_ESCAPES)
        val read = Refinement.Refined(formal)
        assertSame(read, observeCallbackCoverage(observed, CallbackCoverageStage.FORMAL, { it }) { read })
        val supplied = formal.withSupplier(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION))
        observeCallbackCoverage(observed, CallbackCoverageStage.SUPPLIER, { it }) { Refinement.Refined(supplied) }
        assertEquals(
            listOf(IntellijReadPhase.CALLBACK_FORMAL_COVERAGE, IntellijReadPhase.CALLBACK_SUPPLIER_COVERAGE),
            observed.phases,
        )
        assertEquals(
            listOf(
                IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_INCOMPLETE,
                IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_INCOMPLETE,
            ),
            observed.counters,
        )
        assertEquals(IntellijReadTermination.CALLBACK_SUPPLIER_PARAMETER_ESCAPES, observed.reasons.first())
        assertEquals(
            setOf(CallbackInvocationFlowCause.PARAMETER_ESCAPES, CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
            supplied.obligations,
        )
    }

    @Test
    fun `exhaustive formal inventory can still have an incomplete supplier`() {
        val observed = CoverageObservation()
        val formal = snapshot(CallbackInvocationScan.EXHAUSTIVE)
        observeCallbackCoverage(observed, CallbackCoverageStage.FORMAL, { it }) { Refinement.Refined(formal) }
        val supplied = formal.withSupplier(setOf(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING))
        observeCallbackCoverage(observed, CallbackCoverageStage.SUPPLIER, { it }) { Refinement.Refined(supplied) }
        assertEquals(
            listOf(
                IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_COMPLETE,
                IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_INCOMPLETE,
            ),
            observed.counters,
        )
        assertEquals(listOf(IntellijReadTermination.CALLBACK_SUPPLIER_UNRESOLVED_ARGUMENT_MAPPING), observed.reasons)
    }

    @Test
    fun `exhaustive evidence records both success stages without establishing graph authority`() {
        val observed = CoverageObservation()
        for (stage in CallbackCoverageStage.entries) {
            observeCallbackCoverage(observed, stage, { it }) {
                Refinement.Refined(snapshot(CallbackInvocationScan.EXHAUSTIVE))
            }
        }
        assertEquals(
            listOf(
                IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_COMPLETE,
                IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_COMPLETE,
            ),
            observed.counters,
        )
        assertEquals(emptyList<IntellijReadTermination>(), observed.reasons)
    }

    @Test
    fun `contract rejection and cancellation never record stage success`() {
        val observed = CoverageObservation()
        val rejected = Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_SCAN_PROOF)
        assertSame(
            rejected,
            observeCallbackCoverage(
                observed,
                CallbackCoverageStage.FORMAL,
                { value: Refinement<CallbackFormalScanSnapshot, CallbackInvocationFlowFailure> -> value },
            ) {
                rejected
            },
        )
        val cancellation = CancellationException("controlled cancellation")
        assertSame(
            cancellation,
            assertThrows(CancellationException::class.java) {
                observeCallbackCoverage(
                    observed,
                    CallbackCoverageStage.SUPPLIER,
                    { value: CallbackFormalScanSnapshot -> Refinement.Refined(value) },
                ) {
                    throw cancellation
                }
            },
        )
        assertEquals(
            listOf(
                IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_INCOMPLETE,
                IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_INCOMPLETE,
            ),
            observed.counters,
        )
    }

    private fun snapshot(scan: CallbackInvocationScan, vararg causes: CallbackInvocationFlowCause) =
        CallbackFormalScanSnapshot(
            invocations = emptyList(),
            obligations = causes.toSet(),
            owners = emptyList(),
            scan = scan,
            forwarding = CallbackForwardingEvidence.InvocationRoutes,
        )
}

private class CoverageObservation : IntellijReadObservation {
    val phases = mutableListOf<IntellijReadPhase>()
    val counters = mutableListOf<IntellijReadCounter>()
    val reasons = mutableListOf<IntellijReadTermination>()

    override fun phase(value: IntellijReadPhase) {
        phases += value
    }

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        repeat(amount) { counters += counter }
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
        reasons += reason
    }
}
