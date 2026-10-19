package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackCompositionDepthTest {
    private val fixture = CallbackDepthFixture()

    @Test
    fun `observed owner composition admits 64 bodies and qualifies the next body`(): Unit =
        with(fixture) {
            assertDepthLimit(chain(64))
        }

    @Test
    fun `observed and immutable owners share one depth allowance across both transitions`(): Unit =
        with(fixture) {
            val chain = chain(64, immutableDepth = 32)
            val within = (chain.execution as CallbackOwnerExecution.Observed).flow
            val graph = CompleteCallbackFlowGraph.admit(within).value()
            assertEquals(1, graph.edges.filterIsInstance<StaticCallbackEdge.ImmutableUses>().size)
            assertEquals(63, graph.edges.filterIsInstance<StaticCallbackEdge.DirectInvoke>().size)
            assertDepthLimit(chain)
        }

    private fun assertDepthLimit(owner: CallbackBodyBinding): Unit =
        with(fixture) {
            val within = (owner.execution as CallbackOwnerExecution.Observed).flow
            assertTrue(within.obligations.isEmpty())
            assertEquals(64, CompleteCallbackFlowGraph.admit(within).value().edges.size)
            assertEquals(lexical.evidence, CompleteCallbackFlowGraph.admit(within).value().root)

            val beyond = observed(65, owner)
            assertEquals(
                setOf(
                    CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                    CallbackInvocationFlowCause.WORK_LIMIT_REACHED,
                ),
                beyond.obligations,
            )
            assertEquals(CallbackInvocationScan.INCOMPLETE, beyond.scan)
            assertEquals(owner, beyond.ownerBindings.single())
        }
}

private class CallbackDepthFixture {
    private val case = CallbackInvocationFlowFixture()
    val lexical = case.endpoint("depth", 0, 2000, emptyList())
    private val named = with(case) { RelationCallableBody.Named.fromCompiler(lexical.evidence).value() }

    fun chain(depth: Int, immutableDepth: Int = 0): CallbackBodyBinding {
        var owner = observedOwner(observed(1, null))
        for (level in 2..depth) {
            owner = if (level == immutableDepth) immutable(level, owner) else observedOwner(observed(level, owner))
        }
        return owner
    }

    fun observed(depth: Int, owner: CallbackBodyBinding?): CallbackInvocationFlow =
        with(case) {
            val body = body(depth)
            val binding =
                CallbackBindingEvidence.Direct(
                    CallbackDirectInvocationBinding.fromCompiler(
                            lease.identity,
                            occurrence(body.range.startInclusive - 1, body.range.endExclusive + 4),
                            owner?.body ?: named,
                        )
                        .value()
                )
            val obligations =
                if (owner == null) emptySet() else setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
            CallbackInvocationFlow.fromCompiler(
                    lease.identity,
                    body,
                    binding,
                    emptyList(),
                    obligations,
                    CallbackInvocationScan.NOT_APPLICABLE,
                )
                .value()
                .withOwnerBindings(listOfNotNull(owner))
                .value()
        }

    private fun observedOwner(flow: CallbackInvocationFlow): CallbackBodyBinding =
        with(case) {
            val binding = flow.binding as CallbackBindingEvidence.Direct
            CallbackBodyBinding.fromCompiler(
                    flow.body,
                    CallbackBodySupply.DirectInvocation(binding.binding.occurrence),
                    binding,
                    setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                )
                .value()
                .withExecution(flow)
                .value()
        }

    private fun immutable(depth: Int, owner: CallbackBodyBinding): CallbackBodyBinding =
        with(case) {
            val body = body(depth)
            val origin = ImmutableCallbackValueOrigin.Anonymous(body)
            val source = ValueSite.fromCompiler(lexical, body.range, ValueRole.ExpressionResult).value()
            val use = immutableUse(body, origin, source, owner.body)
            val flow =
                ImmutableCallbackInvocationFlow.fromCompiler(
                        origin,
                        source,
                        listOf(use),
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        CallbackInvocationScan.EXHAUSTIVE,
                        listOf(owner),
                    )
                    .value()
                    .withOwnerBindings(emptyList())
                    .value()
            assertTrue(flow.obligations.isEmpty())
            CallbackBodyBinding.fromCompiler(
                    body,
                    CallbackBodySupply.Stored,
                    CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
                    setOf(
                        CallbackInvocationFlowCause.STORED_CALLBACK,
                        CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                    ),
                )
                .value()
                .withExecution(flow, named)
                .value()
        }

    private fun immutableUse(
        body: RelationCallableBody.Anonymous,
        origin: ImmutableCallbackValueOrigin.Anonymous,
        source: ValueSite,
        owner: RelationCallableBody.Anonymous,
    ): ImmutableCallbackInvocationUse.Direct =
        with(case) {
            val local =
                ValueSite.fromCompiler(
                        lexical,
                        range(body.range.startInclusive - 5, body.range.startInclusive - 2),
                        ValueRole.LocalBinding,
                    )
                    .value()
            val destination =
                ValueSite.fromCompiler(
                        lexical,
                        range(body.range.endExclusive + 2, body.range.endExclusive + 4),
                        ValueRole.LocalRead,
                    )
                    .value()
            val value =
                ImmutableCallbackValue.fromCompiler(
                        origin,
                        source,
                        destination,
                        listOf(
                            ValueTransfer.fromCompiler(source, local, ValueTransferKind.LOCAL_BINDING).value(),
                            ValueTransfer.fromCompiler(local, destination, ValueTransferKind.LOCAL_READ).value(),
                        ),
                    )
                    .value()
            val binding =
                CallbackDirectInvocationBinding.fromCompiler(
                        lease.identity,
                        occurrence(body.range.endExclusive + 1, body.range.endExclusive + 6),
                        owner,
                    )
                    .value()
            ImmutableCallbackInvocationUse.Direct(value, binding)
        }

    private fun body(depth: Int) = case.anonymous(depth * 10, 2000 - depth * 10)

    fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Unexpected rejection: $failure")
        }
}
