package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CallbackInvocationFlowRetentionTest {
    private val case = CallbackInvocationFlowFixture()

    @Test
    fun `retained invocation and obligation collections are detached from input mutation`(): Unit =
        with(case) {
            val invocations = mutableListOf(nested)
            val causes = mutableSetOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
            val flow = observed(invocations, causes)
            invocations.clear()
            causes.clear()
            assertEquals(listOf(nested), flow.invocations)
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), flow.obligations)
            assertThrows(UnsupportedOperationException::class.java) {
                (flow.invocations as MutableList<CallbackParameterInvocation>).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (flow.obligations as MutableSet<CallbackInvocationFlowCause>).clear()
            }
        }

    @Test
    fun `immutable callable aliases retain existing value-flow transfers in exact invocation order`(): Unit =
        with(case) {
            val parameterRead = ValueSite.fromCompiler(target, range(250, 255), ValueRole.ExpressionResult).value()
            val local = ValueSite.fromCompiler(target, range(248, 268), ValueRole.LocalBinding).value()
            val read = ValueSite.fromCompiler(target, range(270, 275), ValueRole.LocalRead).value()
            val transfers =
                mutableListOf(
                    ValueTransfer.fromCompiler(parameterRead, local, ValueTransferKind.LOCAL_BINDING).value(),
                    ValueTransfer.fromCompiler(local, read, ValueTransferKind.LOCAL_READ).value(),
                )
            val invoked =
                CallbackParameterInvocation.fromCompiler(
                        occurrence(270, 285),
                        RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                        transfers,
                    )
                    .value()
            val flow = observed(listOf(invoked), emptySet())
            assertEquals(
                listOf(ValueTransferKind.LOCAL_BINDING, ValueTransferKind.LOCAL_READ),
                flow.invocations.single().callableTransfers.map { it.kind },
            )
            assertSame(parameterRead, flow.invocations.single().callableTransfers.first().source)
            assertSame(read, flow.invocations.single().callableTransfers.last().target)
            transfers.clear()
            assertEquals(2, invoked.callableTransfers.size)
            assertThrows(UnsupportedOperationException::class.java) {
                (invoked.callableTransfers as MutableList<ValueTransfer>).clear()
            }
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_CALLABLE_TRANSFER_PATH,
                CallbackParameterInvocation.fromCompiler(
                        occurrence(270, 285),
                        invoked.owner,
                        invoked.callableTransfers.reversed(),
                    )
                    .failure(),
            )
        }
}
