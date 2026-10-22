package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CallbackBodyReadsTest {
    private val request = RelationReadTest().request(RelationMeaning.Callers)
    private val basis = request.subject.lease.identity
    private val binding = CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK)
    private val result = CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)

    @Test
    fun `an active exact body and binding rejects recursion and releases after return`() {
        val reads = CallbackBodyReads(request.budget.resources)
        val body = body(0)
        val recursive =
            reads.read(body, binding, basis) {
                reads.read(body, binding, basis) { error("An active callback must not enter its body twice") }
            }
        assertEquals(CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.CALLBACK_CYCLE), recursive)
        assertEquals(result, reads.read(body, binding, basis) { result })
    }

    @Test
    fun `different body identities share a bounded depth and release after exhaustion`() {
        val reads = CallbackBodyReads(request.budget.resources)
        var entered = 0
        fun descend(index: Int): CallbackInvocationFlowRead =
            reads.read(body(index), binding, basis) {
                entered++
                if (index == 64) error("The sixty-fifth owner must not enter its body") else descend(index + 1)
            }
        assertEquals(CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.WORK_LIMIT_REACHED), descend(0))
        assertEquals(64, entered)
        assertEquals(result, reads.read(body(0), binding, basis) { result })
    }

    @Test
    fun `a failed read releases its active identity`() {
        val reads = CallbackBodyReads(request.budget.resources)
        val body = body(0)
        assertThrows(IllegalStateException::class.java) {
            reads.read(body, binding, basis) { error("Native read failed") }
        }
        assertEquals(result, reads.read(body, binding, basis) { result })
    }

    private fun body(index: Int): RelationCallableBody.Anonymous {
        val range = RelationOccurrence.fromBoundary(request.subject.file, index * 4, index * 4 + 3).refined().range
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(request.subject.file, range),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .refined() as CanonicalCompilerSignature.Function
        return RelationCallableBody.Anonymous.fromCompiler(request.subject.file, range, signature).refined()
    }

    private fun <V, F> Refinement<V, F>.refined(): V = (this as Refinement.Refined).value
}
