package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure
import io.github.amichne.kast.relation.contract.ValueCallbackInputFailure
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite

/** Exercise the production native-to-contract failure projection with a compiler-admitted source site. */
class NativeCallbackInputObligationTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "callback-input-obligation-jdk"

    fun testCallbackReasonSurvivesTheBroadExecutionLimitation() {
        val reason = CallbackInvocationFlowCause.NO_INVOCATION_PROVEN
        val obligation = obligation(NativeCallbackInputFailure.Callback(reason))
        assertEquals(ValueFlowUnsupportedCause.NESTED_EXECUTION, obligation.cause)
        assertEquals(ValueCallbackInputFailure.Callback(reason), detail(obligation))
    }

    fun testContractReasonRemainsDistinctFromCallbackAndGraphFailures() {
        val reason = CallbackInvocationFlowFailure.UNBOUND_INVOCATION
        val obligation = obligation(NativeCallbackInputFailure.Contract(reason))
        assertEquals(ValueCallbackInputFailure.Contract(reason), detail(obligation))
    }

    fun testGraphReasonAndCapacityRemainVisibleTogether() {
        val reason = StaticCallbackGraphFailure.Unavailable(CallbackInvocationFlowCause.WORK_LIMIT_REACHED)
        val obligation = obligation(NativeCallbackInputFailure.Graph(reason))
        assertEquals(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED, obligation.cause)
        assertEquals(ValueCallbackInputFailure.Graph(reason), detail(obligation))
    }

    fun testAllCallbackCapacityCausesRemainCapacityCauses() {
        val expected =
            mapOf(
                CallbackInvocationFlowCause.WORK_LIMIT_REACHED to ValueFlowUnsupportedCause.WORK_LIMIT_REACHED,
                CallbackInvocationFlowCause.TIME_LIMIT_REACHED to ValueFlowUnsupportedCause.TIME_LIMIT_REACHED,
                CallbackInvocationFlowCause.RESULT_LIMIT_REACHED to ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED,
                CallbackInvocationFlowCause.BYTE_LIMIT_REACHED to ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED,
            )
        expected.forEach { (callback, cause) ->
            assertEquals(cause, ValueCallbackInputFailure.Callback(callback).nativeCallbackInputCause())
            assertEquals(
                cause,
                ValueCallbackInputFailure.Graph(StaticCallbackGraphFailure.Unavailable(callback))
                    .nativeCallbackInputCause(),
            )
        }
    }

    private fun detail(obligation: ValueFlowObligation): ValueCallbackInputFailure =
        (obligation.evidence as ValueFlowObligationEvidence.CallbackInput).failure

    private fun obligation(failure: NativeCallbackInputFailure): ValueFlowObligation {
        val fixture = packetFixture(listOf(COVERAGE))
        val actual = fixture.argumentExpression(PacketDeclaration(COVERAGE, "", "invokeParts"), "action", "value")
        val source =
            ValueSite.fromCompiler(RelationEndpoint.subject(actual.enclosing), actual.anchor, ValueRole.LocalRead)
                .nativeRefined()
        return ReadAction.compute<ValueFlowObligation, RuntimeException> {
            val domain = RelationRequest.start(actual.enclosing, RelationMeaning.References, fixture.budget)
            val prepared = IntellijValueFlowCompilerAdapter().prepare(project, domain, fixture.model)
            assertTrue(prepared is IntellijValueFlowCompilerAdapter.Prepared.Ready)
            prepared as IntellijValueFlowCompilerAdapter.Prepared.Ready
            val expression = checkNotNull(prepared.owner.exactValueElement(source.range))
            nativeCallbackInputObligation(source, failure, expression).nativeRefined().also { obligation ->
                assertEquals(source, obligation.site)
            }
        }
    }

    private companion object {
        const val COVERAGE = "src/main/kotlin/fixture/variants/coverage/COVERAGE.kt"
    }
}
