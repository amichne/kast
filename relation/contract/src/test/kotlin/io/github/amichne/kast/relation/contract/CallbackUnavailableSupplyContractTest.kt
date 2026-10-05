package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallbackUnavailableSupplyContractTest {
    private val case = CallbackInvocationFlowFixture()

    @Test
    fun `unsupported direct invocation supply refines the exact callback body without stored evidence`(): Unit =
        with(case) {
            val owner =
                supplied(
                    CallbackBodySupply.Invocation(occurrence(20, 80)),
                    CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
                )
            val flow =
                unbound(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
                    .withOwnerBindings(listOf(owner))
                    .value()
            assertEquals(
                occurrence(20, 80),
                (flow.ownerBindings.single().supply as CallbackBodySupply.Invocation).occurrence,
            )
            assertEquals(
                setOf(
                    CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                    CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
                ),
                flow.obligations,
            )
        }

    @Test
    fun `returned value supply preserves the return occurrence and cannot use stored binding evidence`(): Unit =
        with(case) {
            val supply = CallbackBodySupply.Returned(occurrence(20, 80))
            val owner = supplied(supply, CallbackInvocationFlowCause.RETURNED_CALLBACK)
            val flow = unbound(CallbackInvocationFlowCause.RETURNED_CALLBACK).withOwnerBindings(listOf(owner)).value()
            assertEquals(supply, flow.ownerBindings.single().supply)
            assertEquals(
                CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH,
                CallbackBodyBinding.fromCompiler(
                        body = body,
                        supply = supply,
                        binding = CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
                        obligations =
                            setOf(
                                CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                                CallbackInvocationFlowCause.STORED_CALLBACK,
                            ),
                    )
                    .failure(),
            )
        }

    @Test
    fun `unknown supply stays closed unsupported and rejects a guessed storage fact`(): Unit =
        with(case) {
            val owner =
                supplied(CallbackBodySupply.Unsupported, CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            assertEquals(CallbackBodySupply.Unsupported, owner.supply)
            assertEquals(
                CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH,
                CallbackBodyBinding.fromCompiler(
                        body = body,
                        supply = CallbackBodySupply.Unsupported,
                        binding = CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
                        obligations =
                            setOf(
                                CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                                CallbackInvocationFlowCause.STORED_CALLBACK,
                            ),
                    )
                    .failure(),
            )
        }

    private fun supplied(supply: CallbackBodySupply, cause: CallbackInvocationFlowCause) =
        with(case) {
            CallbackBodyBinding.fromCompiler(
                    body = body,
                    supply = supply,
                    binding = CallbackBindingEvidence.Unavailable(cause),
                    obligations = setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION, cause),
                )
                .value()
        }

    private fun unbound(cause: CallbackInvocationFlowCause) =
        with(case) {
            CallbackInvocationFlow.fromCompiler(
                    basis = lease.identity,
                    body = body,
                    binding = CallbackBindingEvidence.Unavailable(cause),
                    invocations = emptyList(),
                    obligations = setOf(cause),
                )
                .value()
        }
}
