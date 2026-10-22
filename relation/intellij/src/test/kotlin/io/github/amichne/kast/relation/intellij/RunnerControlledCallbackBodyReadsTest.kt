package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RunnerControlledCallbackBodyReadsTest {
    @Test
    fun `runner budget reaches distinct owners beyond the bounded depth`() {
        val request = RelationReadTest().request(RelationMeaning.Callers)
        val reads =
            CallbackParameterSummaries(request.budget.copy(resources = ResourceBudget.RunnerControlled)).bodyReads
        val binding = CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK)
        val terminal = CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        var entered = 0
        fun descend(index: Int): CallbackInvocationFlowRead =
            reads.read(body(request, index), binding, request.subject.lease.identity) {
                entered++
                if (index == 128) terminal else descend(index + 1)
            }
        assertEquals(terminal, descend(0))
        assertEquals(129, entered)
    }

    @Test
    fun `runner budget preserves exact cycle rejection beyond the bounded depth`() {
        val request = RelationReadTest().request(RelationMeaning.Callers)
        val reads =
            CallbackParameterSummaries(request.budget.copy(resources = ResourceBudget.RunnerControlled)).bodyReads
        val binding = CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK)
        fun descend(index: Int): CallbackInvocationFlowRead =
            reads.read(body(request, index), binding, request.subject.lease.identity) {
                if (index == 80)
                    reads.read(body(request, 0), binding, request.subject.lease.identity) {
                        error("An exact active owner cannot enter twice")
                    }
                else descend(index + 1)
            }
        assertEquals(CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.CALLBACK_CYCLE), descend(0))
    }

    @Test
    fun `caller cancellation releases every active runner owner`() {
        val request = RelationReadTest().request(RelationMeaning.Callers)
        val reads =
            CallbackParameterSummaries(request.budget.copy(resources = ResourceBudget.RunnerControlled)).bodyReads
        val binding = CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK)
        fun descend(index: Int): CallbackInvocationFlowRead =
            reads.read(body(request, index), binding, request.subject.lease.identity) {
                if (index == 80) throw CancellationException("The caller stops this read") else descend(index + 1)
            }
        assertThrows(CancellationException::class.java) { descend(0) }
        val terminal = CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        assertEquals(terminal, reads.read(body(request, 0), binding, request.subject.lease.identity) { terminal })
    }
}

private fun body(request: RelationRequest, index: Int): RelationCallableBody.Anonymous {
    val range = RelationOccurrence.fromBoundary(request.subject.file, index * 4, index * 4 + 3).refined().range
    val signature =
        CanonicalCompilerSignature.function(
                RelationCallableBody.Anonymous.sourceIdentity(request.subject.file, range),
                null,
                emptyList(),
                emptyList(),
                0,
            )
            .refined()
    return RelationCallableBody.Anonymous.fromCompiler(
            request.subject.file,
            range,
            assertInstanceOf(CanonicalCompilerSignature.Function::class.java, signature),
        )
        .refined()
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
