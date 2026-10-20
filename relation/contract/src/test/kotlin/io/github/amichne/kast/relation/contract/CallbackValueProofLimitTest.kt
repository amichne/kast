package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class CallbackValueProofLimitTest {
    @Test
    fun `a callback proof keeps 986 expanded frames`() {
        assertInstanceOf(Refinement.Refined::class.java, proof(29))
    }

    @Test
    fun `a callback proof rejects 1020 expanded frames before canonicalization`() {
        val rejection = assertInstanceOf(Refinement.Rejected::class.java, proof(30))
        assertEquals(CallbackInvocationFlowFailure.PROOF_LIMIT_REACHED, rejection.failure)
    }

    private fun proof(frameCount: Int): Refinement<CallbackInvocationFlow, CallbackInvocationFlowFailure> {
        val case = ValueCallbackOracleCase()
        var stack = ValueCallStack.Empty
        repeat(frameCount) { index ->
            val call =
                ValueInvocation.fromCompiler(case.mapper, flowRange(400 + index, 401 + index), case.mapper)
                    .flowRefined()
            val frame = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(case.mapper)).flowRefined()
            stack = stack.enter(frame).flowRefined()
        }
        fun site(start: Int, role: ValueRole) =
            ValueSite.fromCompiler(case.mapper, flowRange(start, start + 1), role, callStack = stack).flowRefined()
        val sites =
            listOf(site(440, ValueRole.ExpressionResult)) +
                (0 until 16).map { site(442 + it * 2, ValueRole.LocalBinding) } +
                site(510, ValueRole.LocalRead)
        val transfers =
            sites.zipWithNext().map { (source, target) ->
                val kind =
                    if (target.role == ValueRole.LocalRead) ValueTransferKind.LOCAL_READ
                    else ValueTransferKind.LOCAL_BINDING
                ValueTransfer.fromCompiler(source, target, kind).flowRefined()
            }
        val selected =
            CallbackParameterInvocation.fromCompiler(
                    case.selected.occurrence,
                    case.selected.owner,
                    transfers,
                )
                .flowRefined()
        return CallbackInvocationFlow.fromCompiler(
            case.flow.basis,
            case.flow.body,
            case.flow.binding,
            listOf(selected),
            emptySet(),
            CallbackInvocationScan.EXHAUSTIVE,
        )
    }
}
