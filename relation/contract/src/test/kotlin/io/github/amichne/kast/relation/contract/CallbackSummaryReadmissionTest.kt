package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CallbackSummaryReadmissionTest {
    private val fixture = CallbackInvocationFlowFixture()
    private val owner = MovingLiveReadAuthorityFixture(fixture.root)

    @Test
    fun `re-admission preserves recursive closing edge and replaces every semantic basis`(): Unit =
        with(fixture) {
            val prior = owner.admit()
            val old = RelationEndpoint.resolve(prior, target.scope, target.evidence, target.constraints).value()
            val formal = CallbackParameterIdentity.fromCompiler(old, binding.position, binding.parameter).value()
            val oldCall = ValueInvocation.fromCompiler(old, range(320, 370), old).value()
            val supplied =
                CallbackArgumentBinding.fromCompiler(
                        oldCall,
                        RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                        binding.position,
                        binding.parameter,
                    )
                    .value()
            val edge = CallbackParameterForwarding.fromCompiler(formal, occurrence(340, 345), supplied).value()
            val graph =
                CompleteCallbackForwardingGraph.fromCompiler(
                        formal,
                        listOf(formal),
                        listOf(edge),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val summary =
                CallbackParameterSummary.fromCompiler(
                        formal,
                        emptyList(),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                        forwarding = CallbackForwardingEvidence.ExhaustedGraph(graph),
                    )
                    .value()
            val current = owner.advance()
            val restored = RelationEndpoint.resolve(current, target.scope, target.evidence, target.constraints).value()
            val admitted =
                CallbackEndpointReadmissions.fromCompiler(current, mapOf(old to restored))
                    .value()
                    .readmit(summary)
                    .value()
            val currentGraph = (admitted.forwarding as CallbackForwardingEvidence.ExhaustedGraph).graph
            assertEquals(current, admitted.formal.callable.lease)
            assertEquals(current, currentGraph.forwardings.single().source.callable.lease)
            assertEquals(current.identity, currentGraph.forwardings.single().target.invocation.basis)
            assertEquals(listOf(formal.parameter), currentGraph.formals.map { it.parameter })
            assertEquals(summary.invocations, admitted.invocations)
            assertNotEquals(summary.formal, admitted.formal)
            assertEquals(prior, summary.formal.callable.lease)
        }

    @Test
    fun `missing or changed compiler restoration cannot re-admit a summary`(): Unit =
        with(fixture) {
            val prior = owner.admit()
            val old = RelationEndpoint.resolve(prior, target.scope, target.evidence, target.constraints).value()
            val formal = CallbackParameterIdentity.fromCompiler(old, binding.position, binding.parameter).value()
            val summary =
                CallbackParameterSummary.fromCompiler(
                        formal,
                        emptyList(),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val current = owner.advance()
            val absent = CallbackEndpointReadmissions.fromCompiler(current, emptyMap()).value().readmit(summary)
            assertEquals(
                CallbackSummaryReadmissionFailure.MissingEndpoint(old.valueIdentity),
                (absent as Refinement.Rejected).failure,
            )
            val wrong = RelationEndpoint.resolve(current, caller.scope, caller.evidence, caller.constraints).value()
            assertInstanceOf(
                Refinement.Rejected::class.java,
                CallbackEndpointReadmissions.fromCompiler(current, mapOf(old to wrong)),
            )
            assertInstanceOf(
                Refinement.Rejected::class.java,
                CallbackEndpointReadmissions.fromCompiler(current, mapOf(old to old)),
            )
        }

    @Test
    fun `intervening movement rejects otherwise complete endpoint restorations`(): Unit =
        with(fixture) {
            val prior = owner.admit()
            val old = RelationEndpoint.resolve(prior, target.scope, target.evidence, target.constraints).value()
            val formal = CallbackParameterIdentity.fromCompiler(old, binding.position, binding.parameter).value()
            val summary =
                CallbackParameterSummary.fromCompiler(
                        formal,
                        emptyList(),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val current = owner.advance()
            val restored = RelationEndpoint.resolve(current, target.scope, target.evidence, target.constraints).value()
            val mapping = CallbackEndpointReadmissions.fromCompiler(current, mapOf(old to restored)).value()
            owner.advance()
            assertInstanceOf(
                CallbackSummaryReadmissionFailure.Authority::class.java,
                (mapping.readmit(summary) as Refinement.Rejected).failure,
            )
        }
}
