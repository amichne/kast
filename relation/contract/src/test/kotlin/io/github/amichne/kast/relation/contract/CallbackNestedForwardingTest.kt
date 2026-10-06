package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallbackNestedForwardingTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `forwarding in a nested named function retains activation uncertainty`(): Unit =
        with(fixture) {
            val receiver = endpoint("sink", 410, 500, listOf("()->Unit"))
            val deferred = endpoint("deferred", 250, 300, emptyList())
            val source = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val mapped =
                CallbackArgumentBinding.fromCompiler(
                        ValueInvocation.fromCompiler(target, range(260, 280), receiver).value(),
                        RelationCallableBody.Named.fromCompiler(deferred.evidence).value(),
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(425, 445),
                    )
                    .value()
            val hop = CallbackParameterForwarding.fromCompiler(source, occurrence(268, 273), mapped).value()
            val terminal =
                CallbackParameterInvocation.fromCompiler(
                        occurrence(470, 480),
                        RelationCallableBody.Named.fromCompiler(receiver.evidence).value(),
                        forwardings = listOf(hop),
                    )
                    .value()
            assertEquals(CallbackInvocationFlowFailure.MISSING_OBLIGATION, create(binding, listOf(terminal)).failure())
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.Bound(binding),
                        listOf(terminal),
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            assertEquals(CallbackInvocationScan.EXHAUSTIVE, flow.scan)
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), flow.obligations)
            assertEquals(mapped.invocationOwner, flow.invocations.single().forwardings.single().target.invocationOwner)
        }
}
