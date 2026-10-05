package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallbackInvocationFlowAdmissionTest {
    private val case = CallbackInvocationFlowFixture()

    @Test
    fun `binding rejects wrong basis body parameter and formal position`(): Unit =
        with(case) {
            val moved = SemanticReadLease(root, EvidenceGeneration.parse(2).value())
            assertEquals(
                CallbackInvocationFlowFailure.BASIS_MISMATCH,
                CallbackInvocationFlow.fromCompiler(
                        basis = moved.identity,
                        body = body,
                        binding = CallbackBindingEvidence.Bound(binding),
                        invocations = listOf(nested),
                        obligations = emptySet(),
                    )
                    .failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT,
                CallbackInvocationFlow.fromCompiler(
                        basis = lease.identity,
                        body = anonymous(90, 100),
                        binding = CallbackBindingEvidence.Bound(binding),
                        invocations = listOf(nested),
                        obligations = emptySet(),
                    )
                    .failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.PARAMETER_OUTSIDE_CALLABLE,
                CallbackArgumentBinding.fromCompiler(
                        invocation = invocation,
                        invocationOwner = supplyingOwner,
                        position = binding.position,
                        parameter = occurrence(190, 205),
                    )
                    .failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_PARAMETER_POSITION,
                CallbackArgumentBinding.fromCompiler(
                        invocation = invocation,
                        invocationOwner = supplyingOwner,
                        position = ValueArgumentPosition.parse(2).value(),
                        parameter = binding.parameter,
                    )
                    .failure(),
            )
        }

    @Test
    fun `invocation must belong to exact mapped target and its own callable body`(): Unit =
        with(case) {
            assertEquals(
                CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_SUPPLYING_OWNER,
                CallbackArgumentBinding.fromCompiler(
                        invocation = invocation,
                        invocationOwner = anonymous(90, 150),
                        position = binding.position,
                        parameter = binding.parameter,
                    )
                    .failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_OWNER,
                CallbackParameterInvocation.fromCompiler(nested.occurrence, anonymous(286, 300)).failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_CALLABLE,
                create(
                        binding,
                        listOf(
                            CallbackParameterInvocation.fromCompiler(occurrence(410, 420), anonymous(405, 425)).value()
                        ),
                    )
                    .failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.DUPLICATE_INVOCATION,
                create(binding, listOf(nested, nested)).failure(),
            )
        }

    @Test
    fun `unresolved and stored callbacks retain finite obligations and cannot contain unbound invocation facts`():
        Unit =
        with(case) {
            val evidence = CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK)
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        basis = lease.identity,
                        body = body,
                        binding = evidence,
                        invocations = emptyList(),
                        obligations = setOf(CallbackInvocationFlowCause.STORED_CALLBACK),
                    )
                    .value()
            assertEquals(evidence, flow.binding)
            assertEquals(
                CallbackInvocationFlowFailure.UNBOUND_INVOCATION,
                CallbackInvocationFlow.fromCompiler(
                        basis = lease.identity,
                        body = body,
                        binding = evidence,
                        invocations = listOf(nested),
                        obligations = setOf(CallbackInvocationFlowCause.STORED_CALLBACK),
                    )
                    .failure(),
            )
            assertEquals(CallbackInvocationFlowFailure.MISSING_OBLIGATION, create(binding, emptyList()).failure())
            val unresolved = observed(emptyList(), setOf(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN))
            assertEquals(emptyList<CallbackParameterInvocation>(), unresolved.invocations)
        }
}
