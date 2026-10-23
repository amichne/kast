package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.LocalBindingReadRemainder
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowSuspensionCause
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Detached result projection only: no native PSI/K2 success or semantic authority is simulated. */
class ValueFlowObservationTest {
    @Test
    fun `supported exhausted read emits a distinct success without changing its evidence`() {
        val read = ValueFlowRead.Observed(step(ValueFlowTerminal.SupportedDomainExhausted))
        val observation = FlowObservation()
        assertSame(read, read.also(observation::valueFlowRead))
        assertEquals(listOf("VALUE_FLOW_SUPPORTED_DOMAIN_EXHAUSTED"), observation.reasons.map { it.name })
        assertEquals(emptyList<IntellijReadCounter>(), observation.counters)
    }

    @Test
    fun `every unsupported arrival emits its exact finite cause instead of complete`() {
        val expected =
            listOf(
                ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED to "VALUE_FLOW_FINALLY_UNSUPPORTED",
                ValueFlowUnsupportedCause.ABRUPT_COMPLETION to "VALUE_FLOW_ABRUPT_COMPLETION",
                ValueFlowUnsupportedCause.EXTERNAL_CALL to "VALUE_FLOW_EXTERNAL_CALL",
                ValueFlowUnsupportedCause.UNMODELED_CALL to "VALUE_FLOW_UNMODELED_CALL",
                ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW to "VALUE_FLOW_MUTABLE_CONTROL_FLOW",
                ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION to "VALUE_FLOW_UNSUPPORTED_EXPRESSION",
                ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE to "VALUE_FLOW_UNRESOLVED_REFERENCE",
                ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY to "VALUE_FLOW_UNSUPPORTED_PROPERTY",
                ValueFlowUnsupportedCause.UNSUPPORTED_RETURN to "VALUE_FLOW_UNSUPPORTED_RETURN",
                ValueFlowUnsupportedCause.NESTED_EXECUTION to "VALUE_FLOW_NESTED_EXECUTION",
                ValueFlowUnsupportedCause.OUTSIDE_DOMAIN to "VALUE_FLOW_OUTSIDE_DOMAIN",
                ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED to "VALUE_FLOW_RESULT_LIMIT_REACHED",
                ValueFlowUnsupportedCause.WORK_LIMIT_REACHED to "VALUE_FLOW_WORK_LIMIT_REACHED",
                ValueFlowUnsupportedCause.TIME_LIMIT_REACHED to "VALUE_FLOW_TIME_LIMIT_REACHED",
                ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED to "VALUE_FLOW_BYTE_LIMIT_REACHED",
                ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED to "VALUE_FLOW_ACCESS_PATH_DEPTH_EXCEEDED",
                ValueFlowUnsupportedCause.CALL_CONTEXT_DEPTH_EXCEEDED to "VALUE_FLOW_CALL_CONTEXT_DEPTH_EXCEEDED",
                ValueFlowUnsupportedCause.RECURSIVE_SOURCE_CALL to "VALUE_FLOW_RECURSIVE_SOURCE_CALL",
            )
        assertEquals(ValueFlowUnsupportedCause.entries.toSet(), expected.map { it.first }.toSet())
        for ((cause, reason) in expected) {
            val read = ValueFlowRead.Observed(step(ValueFlowTerminal.Unresolved, cause))
            val observation = FlowObservation()
            assertSame(read, read.also(observation::valueFlowRead))
            assertEquals(listOf(reason), observation.reasons.map { it.name }, cause.toString())
            assertEquals(emptyList<IntellijReadCounter>(), observation.counters)
        }
    }

    @Test
    fun `suspended reads retain every obligation and the exact resource cause without success`() {
        val expected =
            listOf(
                ValueFlowSuspensionCause.WORK_LIMIT_REACHED to "VALUE_FLOW_SUSPENDED_WORK_LIMIT_REACHED",
                ValueFlowSuspensionCause.RESULT_LIMIT_REACHED to "VALUE_FLOW_SUSPENDED_RESULT_LIMIT_REACHED",
                ValueFlowSuspensionCause.BYTE_LIMIT_REACHED to "VALUE_FLOW_SUSPENDED_BYTE_LIMIT_REACHED",
                ValueFlowSuspensionCause.TIME_LIMIT_REACHED to "VALUE_FLOW_SUSPENDED_TIME_LIMIT_REACHED",
            )
        assertEquals(ValueFlowSuspensionCause.entries.toSet(), expected.map { it.first }.toSet())
        for ((cause, reason) in expected) {
            val step =
                step(
                    ValueFlowTerminal.ResourceSuspended,
                    ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE,
                    ValueFlowUnsupportedCause.NESTED_EXECUTION,
                )
            val remainder =
                LocalBindingReadRemainder.admit(step.source, step.domain.boundary, emptySet(), emptySet()).refined()
            val read = ValueFlowRead.Suspended(step, remainder, cause)
            val observation = FlowObservation()
            assertSame(read, read.also(observation::valueFlowRead))
            assertEquals(
                listOf("VALUE_FLOW_UNRESOLVED_REFERENCE", "VALUE_FLOW_NESTED_EXECUTION", reason),
                observation.reasons.map { it.name },
            )
            assertEquals(emptyList<IntellijReadCounter>(), observation.counters)
        }
    }

    @Test
    fun `rejected reads retain each finite cause and the existing rejection counter`() {
        val expected =
            listOf(
                ValueFlowRejection.STALE_SITE to "VALUE_FLOW_REJECTED_STALE_SITE",
                ValueFlowRejection.OUTSIDE_DOMAIN to "VALUE_FLOW_REJECTED_OUTSIDE_DOMAIN",
                ValueFlowRejection.OWNER_UNAVAILABLE to "VALUE_FLOW_REJECTED_OWNER_UNAVAILABLE",
                ValueFlowRejection.AUTHORITY_MOVED to "VALUE_FLOW_REJECTED_AUTHORITY_MOVED",
                ValueFlowRejection.UNSUPPORTED_SEED to "VALUE_FLOW_REJECTED_UNSUPPORTED_SEED",
                ValueFlowRejection.UNRESOLVED_SEED to "VALUE_FLOW_REJECTED_UNRESOLVED_SEED",
                ValueFlowRejection.NATIVE_UNAVAILABLE to "VALUE_FLOW_REJECTED_NATIVE_UNAVAILABLE",
                ValueFlowRejection.GRANT_TOO_SMALL to "VALUE_FLOW_REJECTED_GRANT_TOO_SMALL",
                ValueFlowRejection.NESTED_EXECUTION to "VALUE_FLOW_REJECTED_NESTED_EXECUTION",
            )
        assertEquals(ValueFlowRejection.entries.toSet(), expected.map { it.first }.toSet())
        for ((cause, reason) in expected) {
            val read = ValueFlowRead.Rejected(cause, RelationWorkCount.parse(0).refined())
            val observation = FlowObservation()
            assertSame(read, read.also(observation::valueFlowRead))
            assertEquals(listOf(reason), observation.reasons.map { it.name }, cause.toString())
            assertEquals(listOf(IntellijReadCounter.VALUE_FLOW_REJECTIONS), observation.counters)
        }
    }

    @Test
    fun `contract rejected reads retain each finite invariant failure without claiming complete`() {
        val expected =
            listOf(
                ValueFlowStepFailure.INVALID_PROGRESS to "VALUE_FLOW_CONTRACT_INVALID_PROGRESS",
                ValueFlowStepFailure.WORK_LIMIT_EXCEEDED to "VALUE_FLOW_CONTRACT_WORK_LIMIT_EXCEEDED",
                ValueFlowStepFailure.RESULT_LIMIT_EXCEEDED to "VALUE_FLOW_CONTRACT_RESULT_LIMIT_EXCEEDED",
                ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED to "VALUE_FLOW_CONTRACT_DETACHED_CAPACITY_EXCEEDED",
                ValueFlowStepFailure.DOMAIN_MISMATCH to "VALUE_FLOW_CONTRACT_DOMAIN_MISMATCH",
                ValueFlowStepFailure.SOURCE_MISMATCH to "VALUE_FLOW_CONTRACT_SOURCE_MISMATCH",
                ValueFlowStepFailure.BASIS_MISMATCH to "VALUE_FLOW_CONTRACT_BASIS_MISMATCH",
                ValueFlowStepFailure.UNRESOLVED_OBLIGATIONS to "VALUE_FLOW_CONTRACT_UNRESOLVED_OBLIGATIONS",
                ValueFlowStepFailure.MISSING_OBLIGATION to "VALUE_FLOW_CONTRACT_MISSING_OBLIGATION",
            )
        assertEquals(ValueFlowStepFailure.entries.toSet(), expected.map { it.first }.toSet())
        for ((cause, reason) in expected) {
            val read = ValueFlowRead.ContractRejected(cause, RelationWorkCount.parse(1).refined())
            val observation = FlowObservation()
            assertSame(read, read.also(observation::valueFlowRead))
            assertEquals(listOf(reason), observation.reasons.map { it.name }, cause.toString())
            assertEquals(listOf(IntellijReadCounter.VALUE_FLOW_REJECTIONS), observation.counters)
        }
    }

    private fun step(terminal: ValueFlowTerminal, vararg causes: ValueFlowUnsupportedCause): ValueFlowStep {
        val domain = RelationReadTest().request(RelationMeaning.References)
        val site = ValueSite.fromCompiler(domain.subject, domain.subject.range, ValueRole.LocalBinding).refined()
        return ValueFlowStep.fromCompiler(
                source = site,
                transfers = emptyList(),
                obligations = causes.map { ValueFlowObligation(site, it) },
                terminal = terminal,
                domain = domain,
                examinedWorkUnits = RelationWorkCount.parse(1).refined(),
            )
            .refined()
    }
}

private class FlowObservation : IntellijReadObservation {
    val reasons = mutableListOf<IntellijReadTermination>()
    val counters = mutableListOf<IntellijReadCounter>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        assertEquals(IntellijReadContributor.NONE, contributor)
        assertEquals(1, amount)
        counters += counter
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
        assertEquals(IntellijReadContributor.NONE, contributor)
        reasons += reason
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Unexpected fixture rejection: $failure")
    }
