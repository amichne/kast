package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackInvocationFlowContractTest {
    private val case = CallbackInvocationFlowFixture()

    @Test
    fun `nested parameter invocation keeps its actual body and exact outer call binding`(): Unit =
        with(case) {
            val flow = observed(listOf(nested), setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION))
            assertSame(body, flow.body)
            assertSame(binding, (flow.binding as CallbackBindingEvidence.Bound).binding)
            assertEquals(1, binding.position.value)
            assertEquals(range(20, 80), binding.invocation.range)
            assertEquals(range(270, 285), flow.invocations.single().occurrence.range)
            assertEquals(range(260, 300), flow.invocations.single().owner.range)
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), flow.obligations)
            assertTrue(flow.retainedBytes > 0)
        }

    @Test
    fun `canonical proof retains context and is independent of allocation and obligation insertion order`(): Unit =
        with(case) {
            val causes =
                linkedSetOf(
                    CallbackInvocationFlowCause.PARAMETER_ESCAPES,
                    CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                )
            val first = observed(listOf(nested), causes)
            val second =
                observed(
                    listOf(CallbackParameterInvocation.fromCompiler(nested.occurrence, nested.owner).value()),
                    causes.reversed().toSet(),
                )
            assertEquals(
                CallbackInvocationFlowRead.Observed(first).canonicalProjection(),
                CallbackInvocationFlowRead.Observed(second).canonicalProjection(),
            )
            val otherInvocation = ValueInvocation.fromCompiler(caller, range(10, 90), target).value()
            val otherBinding =
                CallbackArgumentBinding.fromCompiler(
                        invocation = otherInvocation,
                        invocationOwner = supplyingOwner,
                        position = binding.position,
                        parameter = binding.parameter,
                    )
                    .value()
            val other =
                CallbackInvocationFlow.fromCompiler(
                        basis = lease.identity,
                        body = body,
                        binding = CallbackBindingEvidence.Bound(otherBinding),
                        invocations = listOf(nested),
                        obligations = causes,
                    )
                    .value()
            assertNotEquals(
                CallbackInvocationFlowRead.Observed(first).canonicalProjection(),
                CallbackInvocationFlowRead.Observed(other).canonicalProjection(),
            )
        }

    @Test
    fun `anonymous identity rejects a signature for another source position`(): Unit =
        with(case) {
            assertEquals(
                RelationCallableBodyFailure.SIGNATURE_LOCATION_MISMATCH,
                RelationCallableBody.Anonymous.fromCompiler(file, range(31, 60), body.signature).failure(),
            )
        }

    @Test
    fun `anonymous supplying owner requires its own activation obligation`(): Unit =
        with(case) {
            val nestedBinding =
                CallbackArgumentBinding.fromCompiler(
                        invocation = invocation,
                        invocationOwner = anonymous(10, 100),
                        position = binding.position,
                        parameter = binding.parameter,
                    )
                    .value()
            val direct =
                CallbackParameterInvocation.fromCompiler(
                        occurrence(270, 285),
                        RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                    )
                    .value()
            assertEquals(
                CallbackInvocationFlowFailure.MISSING_OBLIGATION,
                create(nestedBinding, listOf(direct)).failure(),
            )
            val qualified =
                CallbackInvocationFlow.fromCompiler(
                        basis = lease.identity,
                        body = body,
                        binding = CallbackBindingEvidence.Bound(nestedBinding),
                        invocations = listOf(direct),
                        obligations = setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    )
                    .value()
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), qualified.obligations)
        }

    @Test
    fun `nested parameter invocation cannot lose its activation obligation`(): Unit =
        with(case) {
            assertEquals(CallbackInvocationFlowFailure.MISSING_OBLIGATION, create(binding, listOf(nested)).failure())
        }
}
