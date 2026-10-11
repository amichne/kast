package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackForwardingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
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
    fun `empty incomplete supplier records every returned qualification once`() {
        val observed = CoverageObservation()
        val captured = snapshot(CallbackInvocationScan.INCOMPLETE, CallbackInvocationFlowCause.PARAMETER_ESCAPES)
        observeCallbackCoverage(observed, CallbackCoverageStage.FORMAL, { it }) { Refinement.Refined(captured) }
        val qualified = observeCallbackSupplierCoverage(observed) { captured }
        assertEquals(
            setOf(CallbackInvocationFlowCause.PARAMETER_ESCAPES, CallbackInvocationFlowCause.NO_INVOCATION_PROVEN),
            qualified.obligations,
        )
        assertEquals(CallbackInvocationScan.INCOMPLETE, qualified.scan)
        assertEquals(captured.invocations, qualified.invocations)
        assertEquals(captured.owners, qualified.owners)
        assertEquals(captured.forwarding, qualified.forwarding)
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
        assertEquals(
            listOf(
                IntellijReadTermination.CALLBACK_SUPPLIER_PARAMETER_ESCAPES,
                IntellijReadTermination.CALLBACK_SUPPLIER_PARAMETER_ESCAPES,
                IntellijReadTermination.CALLBACK_SUPPLIER_NO_INVOCATION_PROVEN,
            ),
            observed.reasons,
        )
    }

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
        val supplied =
            observeCallbackSupplierCoverage(observed) {
                formal.withSupplier(setOf(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING))
            }
        assertEquals(
            setOf(
                CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING,
                CallbackInvocationFlowCause.NO_INVOCATION_PROVEN,
            ),
            supplied.obligations,
        )
        assertEquals(CallbackInvocationScan.INCOMPLETE, supplied.scan)
        assertEquals(
            listOf(
                IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_COMPLETE,
                IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_INCOMPLETE,
            ),
            observed.counters,
        )
        assertEquals(
            listOf(
                IntellijReadTermination.CALLBACK_SUPPLIER_UNRESOLVED_ARGUMENT_MAPPING,
                IntellijReadTermination.CALLBACK_SUPPLIER_NO_INVOCATION_PROVEN,
            ),
            observed.reasons,
        )
    }

    @Test
    fun `exhaustive evidence records both success stages without establishing graph authority`() {
        val observed = CoverageObservation()
        val exhaustive = snapshot(CallbackInvocationScan.EXHAUSTIVE)
        observeCallbackCoverage(observed, CallbackCoverageStage.FORMAL, { it }) { Refinement.Refined(exhaustive) }
        assertSame(exhaustive, observeCallbackSupplierCoverage(observed) { exhaustive })
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
    fun `incomplete supplier with an invocation preserves its cause without claiming absence`() {
        val fixture = RelationReadTest()
        val request = fixture.request(RelationMeaning.Callees)
        val target = fixture.fact(request).target as RelationEndpoint.Resolved
        val invocation =
            CallbackParameterInvocation.fromCompiler(
                    RelationOccurrence.fromBoundary(target.file, 77, 78).refined(),
                    RelationCallableBody.Named.fromCompiler(target.evidence).refined(),
                )
                .refined()
        val captured =
            snapshot(CallbackInvocationScan.INCOMPLETE, CallbackInvocationFlowCause.PARAMETER_ESCAPES)
                .copy(invocations = listOf(invocation))
        val observed = CoverageObservation()
        assertSame(captured, observeCallbackSupplierCoverage(observed) { captured })
        assertEquals(setOf(CallbackInvocationFlowCause.PARAMETER_ESCAPES), captured.obligations)
        assertEquals(listOf(IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_INCOMPLETE), observed.counters)
        assertEquals(listOf(IntellijReadTermination.CALLBACK_SUPPLIER_PARAMETER_ESCAPES), observed.reasons)
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
                observeCallbackSupplierCoverage(observed) {
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
        assertEquals(
            listOf(IntellijReadPhase.CALLBACK_FORMAL_COVERAGE, IntellijReadPhase.CALLBACK_SUPPLIER_COVERAGE),
            observed.phases,
        )
        assertEquals(emptyList<IntellijReadTermination>(), observed.reasons)
    }

    @Test
    fun `supplier exception propagates unchanged with an entered incomplete stage`() {
        val observed = CoverageObservation()
        val failure = IllegalStateException("controlled native read failure")
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                observeCallbackSupplierCoverage(observed) { throw failure }
            },
        )
        assertEquals(listOf(IntellijReadPhase.CALLBACK_SUPPLIER_COVERAGE), observed.phases)
        assertEquals(listOf(IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_INCOMPLETE), observed.counters)
        assertEquals(emptyList<IntellijReadTermination>(), observed.reasons)
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

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Unexpected fixture rejection: $failure")
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
