package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackOwnerCompositionTest {
    private val case = CallbackInvocationFlowFixture()
    private val receiver = case.endpoint("scope", 410, 500, listOf("()->Unit"))

    @Test
    fun `captured formal invocation composes its independently proven anonymous owner`(): Unit =
        with(case) {
            val owner = owner(target)
            val resolved = captured().withOwnerBindings(listOf(owner)).value()
            assertTrue(resolved.obligations.isEmpty())
            val graph = CompleteCallbackFlowGraph.admit(resolved).value()
            assertEquals(caller.evidence, graph.root)
            val invocation = graph.edges.filterIsInstance<StaticCallbackEdge.Invoke>().single { it.target.body == body }
            assertEquals(StaticCallbackNode.Anonymous(nested.owner as RelationCallableBody.Anonymous), invocation.owner)
            assertEquals(binding, invocation.source.supply)
            assertEquals(nested, invocation.evidence)
            assertTrue((owner.execution as CallbackOwnerExecution.Observed).flow.obligations.isEmpty())
        }

    @Test
    fun `owner mapping alone cannot discharge the captured invocation`(): Unit =
        with(case) {
            val mapped = mapping(target)
            val unresolved = captured().withOwnerBindings(listOf(mapped)).value()
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), unresolved.obligations)
            assertEquals(
                StaticCallbackGraphFailure.MissingNamedOwner,
                CompleteCallbackFlowGraph.admit(unresolved).failure(),
            )
        }

    @Test
    fun `same source ranges with a different lexical identity cannot discharge an owner route`(): Unit =
        with(case) {
            val wrongRoot = endpoint("unrelated", 210, 400, listOf("Project", "()->Unit"))
            val resolvedOwner = owner(wrongRoot)
            assertTrue(resolvedOwner.obligations.isEmpty())
            val unresolved = captured().withOwnerBindings(listOf(resolvedOwner)).value()
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), unresolved.obligations)
            assertEquals(
                StaticCallbackGraphFailure.SupplierIdentityMismatch,
                CompleteCallbackFlowGraph.admit(unresolved).failure(),
            )
        }

    @Test
    fun `execution from a different supplier cannot replace an exact owner binding`(): Unit =
        with(case) {
            val mapped = mapping(target)
            val otherRoot = endpoint("unrelated", 210, 400, listOf("Project", "()->Unit"))
            val other = owner(otherRoot).execution as CallbackOwnerExecution.Observed
            assertEquals(
                CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH,
                mapped.withExecution(other.flow).failure(),
            )
        }

    @Test
    fun `an exhausted owner formal with no invocation remains known without an activation claim`(): Unit =
        with(case) {
            val mapped = mapping(target)
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        mapped.body,
                        mapped.binding,
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val resolved = captured().withOwnerBindings(listOf(mapped.withExecution(flow).value())).value()
            assertTrue(resolved.obligations.isEmpty())
            val graph = CompleteCallbackFlowGraph.admit(resolved).value()
            assertTrue(graph.edges.filterIsInstance<StaticCallbackEdge.Invoke>().none { it.target.body == mapped.body })
            assertEquals(1, graph.edges.filterIsInstance<StaticCallbackEdge.Invoke>().size)
        }

    @Test
    fun `unresolved nesting admits exact owner effect gaps without claiming an exhaustive scan`(): Unit =
        with(case) {
            val mapped = mapping(target)
            val cause = CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN
            val unavailable =
                CallbackBodyBinding.fromCompiler(
                        mapped.body,
                        mapped.supply,
                        CallbackBindingEvidence.Unavailable(cause),
                        setOf(cause, CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    )
                    .value()
            val refined = captured().withOwnerBindings(listOf(unavailable)).value()
            assertEquals(CallbackInvocationScan.INCOMPLETE, refined.scan)
            assertEquals(setOf(cause, CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), refined.obligations)
            assertEquals(listOf(nested), refined.invocations)
        }

    @Test
    fun `complete owner proof cannot upgrade an incomplete source scan`(): Unit =
        with(case) {
            val incomplete =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.Bound(binding),
                        listOf(nested),
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        CallbackInvocationScan.INCOMPLETE,
                    )
                    .value()
            val refined = incomplete.withOwnerBindings(listOf(owner(target))).value()
            assertEquals(CallbackInvocationScan.INCOMPLETE, refined.scan)
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), refined.obligations)
            assertEquals(StaticCallbackGraphFailure.IncompleteScan, CompleteCallbackFlowGraph.admit(refined).failure())
        }

    private fun captured() =
        with(case) {
            CallbackInvocationFlow.fromCompiler(
                    lease.identity,
                    body,
                    CallbackBindingEvidence.Bound(binding),
                    listOf(nested),
                    setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .value()
        }

    private fun mapping(enclosing: RelationEndpoint.Resolved) =
        with(case) {
            val binding =
                CallbackArgumentBinding.fromCompiler(
                        ValueInvocation.fromCompiler(enclosing, range(255, 310), receiver).value(),
                        RelationCallableBody.Named.fromCompiler(enclosing.evidence).value(),
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(425, 445),
                    )
                    .value()
            CallbackBodyBinding.fromCompiler(
                    nested.owner as RelationCallableBody.Anonymous,
                    CallbackBodySupply.Invocation(occurrence(255, 310)),
                    CallbackBindingEvidence.Bound(binding),
                    setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                )
                .value()
        }

    private fun owner(enclosing: RelationEndpoint.Resolved) =
        with(case) {
            val mapped = mapping(enclosing)
            val invocation =
                CallbackParameterInvocation.fromCompiler(
                        occurrence(450, 460),
                        RelationCallableBody.Named.fromCompiler(receiver.evidence).value(),
                    )
                    .value()
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        mapped.body,
                        mapped.binding,
                        listOf(invocation),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            mapped.withExecution(flow).value()
        }
}
