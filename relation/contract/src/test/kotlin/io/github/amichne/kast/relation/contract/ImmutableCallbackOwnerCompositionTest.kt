package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImmutableCallbackOwnerCompositionTest {
    private val fixture = ImmutableCallbackOwnerFixture()

    @Test
    fun `a direct immutable use keeps its actual anonymous owner and discharges only proven nesting`(): Unit =
        with(fixture) {
            val owner = owner()
            val use = direct()
            val flow = flow(use, listOf(owner)).withOwnerBindings(emptyList()).refined()
            assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
            assertEquals(CallbackInvocationScan.EXHAUSTIVE, flow.scan)
            val graph = CompleteCallbackFlowGraph.admit(flow, case.supplyingOwner).refined()
            val edge = graph.edges.filterIsInstance<StaticCallbackEdge.ImmutableUses>().single()
            assertEquals(StaticCallbackNode.Anonymous(ownerBody), edge.source)
            assertEquals(StaticCallbackNode.Anonymous(body), edge.target)
            assertEquals(use, edge.use)
            assertTrue(graph.edges.any { it is StaticCallbackEdge.DirectInvoke && it.target.body == ownerBody })
            assertEquals(Refinement.Refined(Unit), flow.admitCallbackWorkspace(case.root))
        }

    @Test
    fun `a supplied immutable use composes its formal route under the actual supplier owner`(): Unit =
        with(fixture) {
            val use = supplied()
            val flow = flow(use, listOf(owner())).withOwnerBindings(emptyList()).refined()
            assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
            val graph = CompleteCallbackFlowGraph.admit(flow, case.supplyingOwner).refined()
            assertEquals(
                StaticCallbackNode.Anonymous(ownerBody),
                graph.edges.filterIsInstance<StaticCallbackEdge.ImmutableUses>().single().source,
            )
            val invocation = graph.edges.filterIsInstance<StaticCallbackEdge.Invoke>().single()
            assertEquals(use.supplier.binding, invocation.source.supply)
            assertEquals(use.summary.formal, invocation.source.parameter)
            assertEquals(StaticCallbackNode.Anonymous(body), invocation.target)
        }

    @Test
    fun `a supplied summary keeps raw nesting after the aggregate proves its separate owner route`(): Unit =
        with(fixture) {
            val use = supplied()
            val nested = case.anonymous(250, 300)
            val summaryOwner = owner(case.target, nested, case.occurrence(245, 310))
            val invocation = CallbackParameterInvocation.fromCompiler(case.occurrence(270, 285), nested).refined()
            val summary =
                CallbackParameterSummary.fromCompiler(
                        use.summary.formal,
                        listOf(invocation),
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        listOf(summaryOwner),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .refined()
            val selected = ImmutableCallbackInvocationUse.Supplied(use.supplier, summary)
            val flow = flow(selected, listOf(owner())).withOwnerBindings(emptyList()).refined()
            assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), summary.obligations)
            val graph = CompleteCallbackFlowGraph.admit(flow, case.supplyingOwner).refined()
            assertEquals(
                StaticCallbackNode.Anonymous(nested),
                graph.edges.filterIsInstance<StaticCallbackEdge.Invoke>().single().owner,
            )
        }

    @Test
    fun `complete immutable owners cannot upgrade an incomplete source scan`(): Unit =
        with(fixture) {
            val flow =
                ImmutableCallbackInvocationFlow.fromCompiler(
                        origin,
                        source,
                        listOf(direct()),
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        CallbackInvocationScan.INCOMPLETE,
                        listOf(owner()),
                    )
                    .refined()
                    .withOwnerBindings(emptyList())
                    .refined()
            assertEquals(CallbackInvocationScan.INCOMPLETE, flow.scan)
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), flow.obligations)
        }

    @Test
    fun `missing or different named roots cannot discharge an anonymous use`(): Unit =
        with(fixture) {
            val missing = flow(direct(), emptyList()).withOwnerBindings(emptyList()).refined()
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), missing.obligations)
            val wrongRoot = case.endpoint("unrelated", 0, 200, emptyList())
            val wrong = flow(direct(), listOf(owner(wrongRoot))).withOwnerBindings(emptyList()).refined()
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), wrong.obligations)
            assertEquals(
                Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch),
                CompleteCallbackFlowGraph.admit(wrong, case.supplyingOwner),
            )
        }

    @Test
    fun `raw anonymous use needs an explicit gap and strict direct observations stay rejected`(): Unit =
        with(fixture) {
            val use = direct()
            assertEquals(
                Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.MISSING_OBLIGATION),
                ImmutableCallbackInvocationFlow.fromCompiler(
                    origin,
                    source,
                    listOf(use),
                    emptySet(),
                    CallbackInvocationScan.EXHAUSTIVE,
                    listOf(owner()),
                ),
            )
            assertEquals(
                Refinement.Rejected(
                    CompleteCallbackDirectInvocationsFailure.InvalidUse(
                        ImmutableCallbackInvocationFlowFailure.UNPROVEN_SUPPLY_OWNER
                    )
                ),
                CompleteCallbackDirectInvocations.fromCompiler(listOf(use.value), use.binding),
            )
        }

    @Test
    fun `an unknown owner effect preserves its exact gap and incomplete source proof`(): Unit =
        with(fixture) {
            val gap = CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN
            val owner =
                CallbackBodyBinding.fromCompiler(
                        ownerBody,
                        CallbackBodySupply.Invocation(case.occurrence(20, 180)),
                        CallbackBindingEvidence.Unavailable(gap),
                        setOf(gap, CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    )
                    .refined()
            val flow =
                ImmutableCallbackInvocationFlow.fromCompiler(
                        origin,
                        source,
                        listOf(direct()),
                        setOf(gap, CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        CallbackInvocationScan.INCOMPLETE,
                        listOf(owner),
                    )
                    .refined()
                    .withOwnerBindings(emptyList())
                    .refined()
            assertEquals(setOf(gap, CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), flow.obligations)
            assertEquals(CallbackInvocationScan.INCOMPLETE, flow.scan)
        }

    @Test
    fun `a transformed factory value keeps its transport proof without a fabricated original supply`(): Unit =
        with(fixture) {
            val factory = case.endpoint("factory", 210, 400, emptyList())
            val originalBody = case.anonymous(270, 300)
            val original = ValueSite.fromCompiler(factory, originalBody.range, ValueRole.ExpressionResult).refined()
            val originalOrigin = ImmutableCallbackValueOrigin.Anonymous(originalBody)
            val returned =
                ImmutableCallbackValue.fromCompiler(originalOrigin, original, original, emptyList()).refined()
            val call = ValueInvocation.fromCompiler(case.caller, case.range(20, 80), factory).refined()
            val proof =
                CallbackFactoryReturn.fromCompiler(call, returned, emptyList(), case.bodyCalls(originalBody)).refined()
            val use = supplied()
            val transformed =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Returned(proof),
                        call.resultSite(),
                        use.value.destination,
                        listOf(
                            ValueTransfer.fromCompiler(
                                    call.resultSite(),
                                    use.value.destination,
                                    ValueTransferKind.ARGUMENT,
                                )
                                .refined()
                        ),
                    )
                    .refined()
            val supplier =
                CallbackParameterSupplier.fromCompiler(use.supplier.binding, use.supplier.selection, transformed)
                    .refined()
            val selected = ImmutableCallbackInvocationUse.Supplied(supplier, use.summary)
            fun flow(mapping: CallbackBodyBinding) =
                ImmutableCallbackInvocationFlow.fromCompiler(
                        originalOrigin,
                        original,
                        listOf(selected),
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        CallbackInvocationScan.EXHAUSTIVE,
                        listOf(mapping),
                    )
                    .refined()
                    .withOwnerBindings(emptyList())
                    .refined()
            val lexical = RelationCallableBody.Named.fromCompiler(factory.evidence).refined()
            val graph = CompleteCallbackFlowGraph.admit(flow(owner()), lexical).refined()
            assertTrue(graph.edges.none { it is StaticCallbackEdge.Supply || it is StaticCallbackEdge.Invoke })
            assertEquals(selected, graph.edges.filterIsInstance<StaticCallbackEdge.ImmutableUses>().single().use)
            assertTrue(graph.edges.any { it is StaticCallbackEdge.DirectInvoke && it.target.body == ownerBody })
            val wrong = flow(owner(case.endpoint("unrelated", 0, 200, emptyList())))
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), wrong.obligations)
            assertEquals(
                Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch),
                CompleteCallbackFlowGraph.admit(wrong, lexical),
            )
        }
}

private class ImmutableCallbackOwnerFixture {
    val case = CallbackInvocationFlowFixture()
    val ownerBody = case.anonymous(30, 160)
    val body = case.anonymous(60, 80)
    val origin = ImmutableCallbackValueOrigin.Anonymous(body)
    val source = ValueSite.fromCompiler(case.caller, body.range, ValueRole.ExpressionResult).refined()

    fun owner(
        root: RelationEndpoint.Resolved = case.caller,
        ownedBody: RelationCallableBody.Anonymous = ownerBody,
        occurrence: RelationOccurrence = case.occurrence(20, 180),
    ): CallbackBodyBinding {
        val binding =
            CallbackBindingEvidence.Direct(
                CallbackDirectInvocationBinding.fromCompiler(
                        case.lease.identity,
                        occurrence,
                        RelationCallableBody.Named.fromCompiler(root.evidence).refined(),
                    )
                    .refined()
            )
        val flow =
            CallbackInvocationFlow.fromCompiler(
                    case.lease.identity,
                    ownedBody,
                    binding,
                    emptyList(),
                    emptySet(),
                    CallbackInvocationScan.NOT_APPLICABLE,
                )
                .refined()
        return CallbackBodyBinding.fromCompiler(
                ownedBody,
                CallbackBodySupply.DirectInvocation(occurrence),
                binding,
                setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
            )
            .refined()
            .withExecution(flow)
            .refined()
    }

    fun direct(): ImmutableCallbackInvocationUse.Direct {
        val read = ValueSite.fromCompiler(case.caller, case.range(110, 115), ValueRole.LocalRead).refined()
        return ImmutableCallbackInvocationUse.Direct(
            value(read),
            CallbackDirectInvocationBinding.fromCompiler(
                    case.lease.identity,
                    case.occurrence(110, 120),
                    ownerBody,
                )
                .refined(),
        )
    }

    fun supplied(): ImmutableCallbackInvocationUse.Supplied {
        val call = ValueInvocation.fromCompiler(case.caller, case.range(120, 150), case.target).refined()
        val binding =
            CallbackArgumentBinding.fromCompiler(
                    call,
                    ownerBody,
                    case.binding.position,
                    case.binding.parameter,
                )
                .refined()
        val argument =
            ValueSite.fromCompiler(
                    case.caller,
                    case.range(125, 135),
                    ValueRole.Argument(call, binding.position),
                )
                .refined()
        val read = ValueSite.fromCompiler(case.caller, case.range(110, 115), ValueRole.LocalRead).refined()
        val before = value(read)
        val value =
            ImmutableCallbackValue.fromCompiler(
                    origin,
                    source,
                    argument,
                    before.transfers + ValueTransfer.fromCompiler(read, argument, ValueTransferKind.ARGUMENT).refined(),
                )
                .refined()
        val supplier =
            CallbackParameterSupplier.fromCompiler(
                    binding,
                    CallbackSupplierSelection.Explicit(argument),
                    value,
                )
                .refined()
        return ImmutableCallbackInvocationUse.Supplied(supplier, summary(binding))
    }

    private fun summary(binding: CallbackArgumentBinding): CallbackParameterSummary {
        val formal = CallbackParameterIdentity.fromCompiler(case.target, binding.position, binding.parameter).refined()
        val invocation =
            CallbackParameterInvocation.fromCompiler(
                    case.occurrence(270, 285),
                    RelationCallableBody.Named.fromCompiler(case.target.evidence).refined(),
                )
                .refined()
        return CallbackParameterSummary.fromCompiler(
                formal,
                listOf(invocation),
                emptySet(),
                scan = CallbackInvocationScan.EXHAUSTIVE,
            )
            .refined()
    }

    private fun value(destination: ValueSite): ImmutableCallbackValue {
        val local = ValueSite.fromCompiler(case.caller, case.range(90, 100), ValueRole.LocalBinding).refined()
        return ImmutableCallbackValue.fromCompiler(
                origin,
                source,
                destination,
                listOf(
                    ValueTransfer.fromCompiler(source, local, ValueTransferKind.LOCAL_BINDING).refined(),
                    ValueTransfer.fromCompiler(local, destination, ValueTransferKind.LOCAL_READ).refined(),
                ),
            )
            .refined()
    }

    fun flow(use: ImmutableCallbackInvocationUse, owners: List<CallbackBodyBinding>) =
        ImmutableCallbackInvocationFlow.fromCompiler(
                origin,
                source,
                listOf(use),
                setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                CallbackInvocationScan.EXHAUSTIVE,
                owners,
            )
            .refined()

    fun <V> Refinement<V, *>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Unexpected rejection: $failure")
        }
}
