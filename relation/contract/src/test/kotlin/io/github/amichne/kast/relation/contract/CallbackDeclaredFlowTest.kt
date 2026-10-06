package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallbackDeclaredFlowTest {
    private val case = CallbackInvocationFlowFixture()

    @Test
    fun `exhaustive unused default retains formal and body without inventing an invocation`(): Unit =
        with(case) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, occurrence(225, 255)).value()
            val declared = CallbackDefaultBinding.fromCompiler(formal, occurrence(240, 255)).value()
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        anonymous(242, 250),
                        CallbackBindingEvidence.Default(declared),
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            assertEquals(formal, (flow.binding as CallbackBindingEvidence.Default).binding.parameter)
            assertEquals(emptyList<CallbackParameterInvocation>(), flow.invocations)
            assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
            assertEquals(CallbackInvocationScan.EXHAUSTIVE, flow.scan)
            assertEquals(
                CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT,
                CallbackDefaultBinding.fromCompiler(formal, occurrence(254, 260)).failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_SCAN_PROOF,
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        flow.body,
                        flow.binding,
                        emptyList(),
                        setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .failure(),
            )
        }

    @Test
    fun `default invocation in another anonymous body requires activation qualification`(): Unit =
        with(case) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, occurrence(225, 255)).value()
            val declared = CallbackDefaultBinding.fromCompiler(formal, occurrence(240, 255)).value()
            assertEquals(
                CallbackInvocationFlowFailure.MISSING_OBLIGATION,
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        anonymous(242, 250),
                        CallbackBindingEvidence.Default(declared),
                        listOf(nested),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .failure(),
            )
        }

    @Test
    fun `direct invocation retains its actual owner and has no formal scan`(): Unit =
        with(case) {
            val direct =
                CallbackDirectInvocationBinding.fromCompiler(lease.identity, occurrence(20, 80), supplyingOwner).value()
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.Direct(direct),
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.NOT_APPLICABLE,
                    )
                    .value()
            assertEquals(supplyingOwner, (flow.binding as CallbackBindingEvidence.Direct).binding.owner)
            assertEquals(
                CallbackInvocationFlowFailure.UNBOUND_INVOCATION,
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        flow.binding,
                        listOf(nested),
                        emptySet(),
                        CallbackInvocationScan.NOT_APPLICABLE,
                    )
                    .failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_SCAN_PROOF,
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        flow.binding,
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .failure(),
            )
        }

    @Test
    fun `direct invocation inside another anonymous body cannot erase activation uncertainty`(): Unit =
        with(case) {
            val direct =
                CallbackDirectInvocationBinding.fromCompiler(lease.identity, occurrence(20, 80), anonymous(10, 100))
                    .value()
            assertEquals(
                CallbackInvocationFlowFailure.MISSING_OBLIGATION,
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.Direct(direct),
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.NOT_APPLICABLE,
                    )
                    .failure(),
            )
        }

    @Test
    fun `nested known supplies retain work limit when formal mapping cannot complete`(): Unit =
        with(case) {
            val cause = CallbackInvocationFlowCause.WORK_LIMIT_REACHED
            val obligations = setOf(cause, CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
            val supplies =
                listOf(
                    CallbackBodySupply.DefaultParameter(occurrence(20, 80)),
                    CallbackBodySupply.DirectInvocation(occurrence(20, 80)),
                )
            for (supply in supplies) {
                val retained =
                    CallbackBodyBinding.fromCompiler(
                            body,
                            supply,
                            CallbackBindingEvidence.Unavailable(cause),
                            obligations,
                        )
                        .value()
                assertEquals(supply, retained.supply)
                assertEquals(cause, (retained.binding as CallbackBindingEvidence.Unavailable).cause)
                assertEquals(obligations, retained.obligations)
                assertEquals(
                    CallbackInvocationFlowFailure.MISSING_OBLIGATION,
                    CallbackBodyBinding.fromCompiler(
                            body,
                            supply,
                            retained.binding,
                            setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        )
                        .failure(),
                )
            }
        }

    @Test
    fun `owner refinement cannot contradict exhaustive scan proof`(): Unit =
        with(case) {
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.Bound(binding),
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val owner =
                CallbackBodyBinding.fromCompiler(
                        body,
                        CallbackBodySupply.DirectInvocation(occurrence(20, 80)),
                        CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.TIME_LIMIT_REACHED),
                        setOf(
                            CallbackInvocationFlowCause.TIME_LIMIT_REACHED,
                            CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                        ),
                    )
                    .value()
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_SCAN_PROOF,
                flow.withOwnerBindings(listOf(owner)).failure(),
            )
        }

    @Test
    fun `forwarding preserves exact source formal and destination before terminal invocation`(): Unit =
        with(case) {
            val receiver = endpoint("next", 410, 500, listOf("()->Unit"))
            val mapped = forwardingBinding(receiver)
            val source = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val forwarded = CallbackParameterForwarding.fromCompiler(source, occurrence(268, 273), mapped).value()
            val terminal =
                CallbackParameterInvocation.fromCompiler(
                        occurrence(470, 480),
                        RelationCallableBody.Named.fromCompiler(receiver.evidence).value(),
                        forwardings = listOf(forwarded),
                    )
                    .value()
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.Bound(binding),
                        listOf(terminal),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val unforwarded = CallbackParameterInvocation.fromCompiler(terminal.occurrence, terminal.owner).value()
            org.junit.jupiter.api.Assertions.assertTrue(terminal.retainedBytes > unforwarded.retainedBytes)
            assertEquals(source, flow.invocations.single().forwardings.single().source)
            assertEquals(mapped, flow.invocations.single().forwardings.single().target)
            val wrongFormal =
                CallbackParameterIdentity.fromCompiler(
                        target,
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(215, 223),
                    )
                    .value()
            val wrongHop = CallbackParameterForwarding.fromCompiler(wrongFormal, occurrence(268, 273), mapped).value()
            val wrongInvocation =
                CallbackParameterInvocation.fromCompiler(
                        terminal.occurrence,
                        terminal.owner,
                        forwardings = listOf(wrongHop),
                    )
                    .value()
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH,
                create(binding, listOf(wrongInvocation)).failure(),
            )
        }

    private fun forwardingBinding(receiver: RelationEndpoint.Resolved): CallbackArgumentBinding =
        with(case) {
            val owner = RelationCallableBody.Named.fromCompiler(target.evidence).value()
            val supply = ValueInvocation.fromCompiler(target, range(260, 280), receiver).value()
            CallbackArgumentBinding.fromCompiler(
                    supply,
                    owner,
                    ValueArgumentPosition.parse(0).value(),
                    occurrence(425, 445),
                )
                .value()
        }
}
