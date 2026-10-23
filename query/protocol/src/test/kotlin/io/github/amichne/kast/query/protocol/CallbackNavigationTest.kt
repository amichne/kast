package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryCallbackNavigationDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackBodySupply
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterForwarding
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.StaticCallbackEdge
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.traversal.contract.CallbackTraversal
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalExtent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure traversal of explicit compiler facts; native resolution has its own fixture. */
internal class CallbackNavigationTest : CompleteOnlyCallbackQueryCase() {
    @Test
    fun `one-row retained pages preserve the full navigation without semantic replay`() = runTest {
        val script =
            Script(
                listOf(listOf(row)),
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        relationObservations = listOf(observation(true)),
                    )
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val produced =
            protocol.execute(
                strict.copy(retention = QueryRetentionModeDocument.RETAIN),
                fixture.authority,
                budget,
                policy(),
            ) as OperationOutcome.Complete
        val reference = (produced.evidence.payload.retention as QueryResultRetention.Retained).reference
        val read = QueryRunRequest.ReadResult.symbols(reference, output = output)
        val large = protocol.executePage(read, fixture.authority, budget) as OperationOutcome.Complete
        val smallBudget =
            budget.copy(resources = budget.resources.bounded().copy(resultLimit = ResultLimit.parse(1).refined()))
        val small = protocol.executePage(read, fixture.authority, smallBudget) as OperationOutcome.Complete
        val replay = protocol.executePage(read, fixture.authority, smallBudget) as OperationOutcome.Complete
        assertEquals(large.evidence.payload.relationObservations, small.evidence.payload.relationObservations)
        assertEquals(small.evidence.payload.relationObservations, replay.evidence.payload.relationObservations)
        val callback = small.evidence.payload.relationObservations.values.single().callbackObservations.values.single()
        val route = callback.navigation as QueryCallbackNavigationDocument.Proven
        assertEquals(listOf(1, 2, 3), route.steps.values.map { it.depth.value })
        assertEquals(1, script.calls)
        script.assertDrained()
    }

    @Test
    fun `depth counts supply invocation and target edges and retains the exact cut frontier`() {
        val graph = CompleteStaticCallbackGraph.admit(observation(true).callbackObservations.single()).refined()
        val supplyOnly =
            CallbackTraversal.walk(graph, TraversalExtent.ThroughDepth(TraversalDepthLimit.parse(1).refined()))
        assertEquals(listOf(StaticCallbackEdge.Supply::class), supplyOnly.steps.map { it.edge::class })
        assertEquals(listOf(StaticCallbackEdge.Invoke::class), supplyOnly.frontier.map { it::class })
        val invoked =
            CallbackTraversal.walk(graph, TraversalExtent.ThroughDepth(TraversalDepthLimit.parse(2).refined()))
        assertEquals(listOf(1, 2), invoked.steps.map { it.depth.value })
        assertEquals(listOf(StaticCallbackEdge.BodyTarget::class), invoked.frontier.map { it::class })
        val all = CallbackTraversal.walk(graph)
        assertEquals(listOf(1, 2, 3), all.steps.map { it.depth.value })
        assertEquals(graph.edges.toSet(), all.steps.map { it.edge }.toSet())
        assertTrue(all.frontier.isEmpty())
        assertTrue(all.unsupported.isEmpty())
    }

    @Test
    fun `supplied but uninvoked callback does not reach its body target`() {
        val original = observation(true).callbackObservations.single()
        val flow = (original.flow as CallbackInvocationFlowRead.Observed).flow
        val unused = replaceFlow(original, flowWith(flow, emptyList()))
        val graph = CompleteStaticCallbackGraph.admit(unused).refined()
        assertTrue(graph.edges.any { it is StaticCallbackEdge.BodyTarget })
        val walked = CallbackTraversal.walk(graph)
        assertEquals(listOf(StaticCallbackEdge.Supply::class), walked.steps.map { it.edge::class })
        assertTrue(walked.steps.none { it.edge is StaticCallbackEdge.BodyTarget })
    }

    @Test
    fun `failed inner invocation retains proven outer route and exact obligation`() {
        val original = observation(true).callbackObservations.single()
        val outer = (original.flow as CallbackInvocationFlowRead.Observed).flow
        val inner = unsupportedInner(outer)
        val callback =
            RelationCallbackObservation.fromNativeBoundary(
                    RelationRequest.start(
                        fixture.selector,
                        RelationMeaning.Callees,
                        RelationPagingFixture.published().budget,
                    ),
                    original.occurrence,
                    original.target,
                    original.lexicalOwner,
                    occurrence(2, 3),
                    original.policy,
                    CallbackInvocationFlowRead.Observed(inner),
                    original.resultProjection,
                )
                .refined()
        val navigation =
            callback.projectNavigation(CallbackProjection(fixture.references, fixture.authority))
                as QueryCallbackNavigationDocument.Unproven
        assertEquals(listOf(1, 2), navigation.prefix.values.map { it.depth.value })
        assertEquals(
            setOf(CallbackInvocationFlowCause.PARAMETER_ESCAPES, CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
            inner.obligations,
        )
        assertEquals(inner.body, (callback.flow as CallbackInvocationFlowRead.Observed).flow.body)
        assertEquals(outer.body, inner.ownerBindings.single().body)
    }

    private fun unsupportedInner(outer: CallbackInvocationFlow): CallbackInvocationFlow {
        val bound = (outer.binding as CallbackBindingEvidence.Bound).binding
        val binding =
            CallbackArgumentBinding.fromCompiler(
                    ValueInvocation.fromCompiler(bound.invocation.enclosing, range(1, 4), bound.invocation.callable)
                        .refined(),
                    outer.body,
                    bound.position,
                    bound.parameter,
                )
                .refined()
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(fixture.selector.file, range(2, 3)),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .refined() as CanonicalCompilerSignature.Function
        val owner =
            CallbackBodyBinding.fromCompiler(
                    outer.body,
                    CallbackBodySupply.Invocation(occurrence(0, 5)),
                    outer.binding,
                    setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                )
                .refined()
                .withExecution(outer)
                .refined()
        return CallbackInvocationFlow.fromCompiler(
                outer.basis,
                RelationCallableBody.Anonymous.fromCompiler(fixture.selector.file, range(2, 3), signature).refined(),
                CallbackBindingEvidence.Bound(binding),
                emptyList(),
                setOf(
                    CallbackInvocationFlowCause.PARAMETER_ESCAPES,
                    CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                ),
                CallbackInvocationScan.INCOMPLETE,
            )
            .refined()
            .withOwnerBindings(listOf(owner))
            .refined()
    }

    @Test
    fun `same formal at distinct suppliers keeps distinct invocation contexts`() {
        val original = observation(true).callbackObservations.single()
        val flow = (original.flow as CallbackInvocationFlowRead.Observed).flow
        val binding = (flow.binding as CallbackBindingEvidence.Bound).binding
        val other =
            CallbackArgumentBinding.fromCompiler(
                    ValueInvocation.fromCompiler(binding.invocation.enclosing, range(1, 5), binding.invocation.callable)
                        .refined(),
                    binding.invocationOwner,
                    binding.position,
                    binding.parameter,
                )
                .refined()
        val second =
            CallbackInvocationFlow.fromCompiler(
                    flow.basis,
                    flow.body,
                    CallbackBindingEvidence.Bound(other),
                    flow.invocations,
                    flow.obligations,
                    flow.scan,
                )
                .refined()
        val firstRoute = CallbackTraversal.walk(CompleteStaticCallbackGraph.admit(original).refined())
        val secondRoute =
            CallbackTraversal.walk(CompleteStaticCallbackGraph.admit(replaceFlow(original, second)).refined())
        val first = firstRoute.steps.first().edge as StaticCallbackEdge.Supply
        val next = secondRoute.steps.first().edge as StaticCallbackEdge.Supply
        assertEquals(first.target.parameter, next.target.parameter)
        assertNotEquals(first.target, next.target)
        assertNotEquals(first.binding.invocation, next.binding.invocation)
        assertEquals(other, next.target.supply)
    }

    @Test
    fun `cyclic callback route terminates in its existing finite graph failure`() {
        val original = observation(true).callbackObservations.single()
        val flow = (original.flow as CallbackInvocationFlowRead.Observed).flow
        val binding = (flow.binding as CallbackBindingEvidence.Bound).binding
        val wrapper = binding.invocation.callable
        val formal = CallbackParameterIdentity.fromCompiler(wrapper, binding.position, binding.parameter).refined()
        val owner = RelationCallableBody.Named.fromCompiler(wrapperEvidence()).refined()
        val recursive =
            CallbackArgumentBinding.fromCompiler(
                    ValueInvocation.fromCompiler(wrapper, range(150, 180), wrapper).refined(),
                    owner,
                    binding.position,
                    binding.parameter,
                )
                .refined()
        val forwarding = CallbackParameterForwarding.fromCompiler(formal, occurrence(155, 158), recursive).refined()
        val invocation =
            CallbackParameterInvocation.fromCompiler(occurrence(160, 170), owner, forwardings = listOf(forwarding))
                .refined()
        val rejected = CompleteStaticCallbackGraph.admit(replaceFlow(original, flowWith(flow, listOf(invocation))))
        assertEquals(StaticCallbackGraphFailure.CyclicRoute, (rejected as Refinement.Rejected).failure)
    }

    private fun flowWith(flow: CallbackInvocationFlow, invocations: List<CallbackParameterInvocation>) =
        CallbackInvocationFlow.fromCompiler(
                flow.basis,
                flow.body,
                flow.binding,
                invocations,
                flow.obligations,
                flow.scan,
            )
            .refined()

    private fun replaceFlow(original: RelationCallbackObservation, flow: CallbackInvocationFlow) =
        RelationCallbackObservation.fromNativeBoundary(
                RelationRequest.start(
                    fixture.selector,
                    RelationMeaning.Callees,
                    RelationPagingFixture.published().budget,
                ),
                original.occurrence,
                original.target,
                original.lexicalOwner,
                original.callbackBody,
                original.policy,
                CallbackInvocationFlowRead.Observed(flow),
                original.resultProjection,
            )
            .refined()
}
