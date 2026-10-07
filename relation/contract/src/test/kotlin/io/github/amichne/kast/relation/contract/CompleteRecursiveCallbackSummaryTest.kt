package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompleteRecursiveCallbackSummaryTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `qualified supplier preserves the exhausted formal graph and its detached footprint`(): Unit =
        with(fixture) {
            val root = formal()
            val edge = forward(root, root, 260, 280)
            val proof = graph(root, listOf(root), listOf(edge))
            val evidence = CallbackForwardingEvidence.ExhaustedGraph(proof)
            val rejected =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.Bound(binding),
                        emptyList(),
                        setOf(CallbackInvocationFlowCause.PARAMETER_ESCAPES),
                        CallbackInvocationScan.INCOMPLETE,
                        evidence,
                    )
                    .value()
            assertSame(
                proof,
                (rejected.withOwnerBindings(emptyList()).value().forwarding
                        as CallbackForwardingEvidence.ExhaustedGraph)
                    .graph,
            )
            assertTrue(
                CompleteStaticCallbackGraph.admit(observation(rejected)).failure()
                    is StaticCallbackGraphFailure.Unresolved
            )
            assertEquals(root.retainedBytes + edge.retainedBytes, proof.retainedBytes)
            val large = endpoint("large", 410, 550, listOf("x".repeat(10_000)))
            val largeFormal =
                CallbackParameterIdentity.fromCompiler(
                        large,
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(425, 445),
                    )
                    .value()
            val largeEdge = forward(root, largeFormal, 260, 280)
            val small = endpoint("large", 410, 550, listOf("()->Unit"))
            val smallFormal =
                CallbackParameterIdentity.fromCompiler(small, largeFormal.position, occurrence(425, 445)).value()
            val smallEdge = forward(root, smallFormal, 260, 280)
            val additionalSignatureBytes = (10_000L - "()->Unit".length) * 2L
            assertTrue(largeEdge.retainedBytes >= smallEdge.retainedBytes + additionalSignatureBytes)
            assertTrue(
                graph(root, listOf(root, largeFormal), listOf(largeEdge)).retainedBytes >=
                    graph(root, listOf(root, smallFormal), listOf(smallEdge)).retainedBytes + additionalSignatureBytes
            )
        }

    @Test
    fun `closed recursive formal graph retains its edge without inventing an invocation`(): Unit =
        with(fixture) {
            val root = formal()
            val edge = forward(root, root, 260, 280)
            val proof = graph(root, listOf(root), listOf(edge))
            val flow = flow(proof, emptyList())
            val admitted = CompleteStaticCallbackGraph.admit(observation(flow)).value()
            val retained = admitted.edges.filterIsInstance<StaticCallbackEdge.Forward>().single()
            assertEquals(retained.source, retained.target)
            assertSame(edge, retained.evidence)
            assertEquals(binding, retained.source.supply)
            assertTrue(admitted.edges.none { it is StaticCallbackEdge.Invoke })
        }

    @Test
    fun `mutual recursion exhausts each formal and preserves each supplier's own graph context`(): Unit =
        with(fixture) {
            val root = formal()
            val destination = endpoint("forward", 410, 550, listOf("()->Unit"))
            val other =
                CallbackParameterIdentity.fromCompiler(
                        destination,
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(425, 445),
                    )
                    .value()
            val outward = forward(root, other, 260, 280)
            val returnEdge = forward(other, root, 460, 480)
            val terminal =
                CallbackParameterInvocation.fromCompiler(
                        occurrence(510, 520),
                        RelationCallableBody.Named.fromCompiler(destination.evidence).value(),
                        forwardings = listOf(outward),
                    )
                    .value()
            val proof = graph(root, listOf(root, other), listOf(outward, returnEdge))
            val summary =
                CallbackParameterSummary.fromCompiler(
                        root,
                        listOf(terminal),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                        forwarding = CallbackForwardingEvidence.ExhaustedGraph(proof),
                    )
                    .value()
            val firstFlow = summary.instantiate(body, binding).value()
            val secondBinding = secondBinding()
            val secondBody = anonymous(120, 150)
            val secondFlow = summary.instantiate(secondBody, secondBinding).value()
            assertSame(proof, (secondFlow.forwarding as CallbackForwardingEvidence.ExhaustedGraph).graph)
            val first = CompleteStaticCallbackGraph.admit(observation(firstFlow)).value()
            val second = CompleteStaticCallbackGraph.admit(observation(secondFlow)).value()
            assertEquals(2, first.edges.filterIsInstance<StaticCallbackEdge.Forward>().size)
            assertEquals(2, first.nodes.filterIsInstance<StaticCallbackNode.Formal>().size)
            assertEquals(body, first.edges.filterIsInstance<StaticCallbackEdge.Invoke>().single().target.body)
            assertEquals(secondBody, second.edges.filterIsInstance<StaticCallbackEdge.Invoke>().single().target.body)
            assertNotEquals(
                first.nodes.filterIsInstance<StaticCallbackNode.Formal>().toSet(),
                second.nodes.filterIsInstance<StaticCallbackNode.Formal>().toSet(),
            )
            assertTrue(second.nodes.filterIsInstance<StaticCallbackNode.Formal>().all { it.supply == secondBinding })
            assertEquals(firstFlow.forwarding, firstFlow.withOwnerBindings(emptyList()).value().forwarding)
        }

    @Test
    fun `missing unreachable duplicate and incomplete formal inventories cannot prove exhaustion`(): Unit =
        with(fixture) {
            val root = formal()
            val destination = endpoint("forward", 410, 550, listOf("()->Unit"))
            val other =
                CallbackParameterIdentity.fromCompiler(
                        destination,
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(425, 445),
                    )
                    .value()
            val edge = forward(root, other, 260, 280)
            for ((formals, edges) in
                listOf(
                    listOf(root) to listOf(edge),
                    listOf(root, other) to emptyList(),
                    listOf(root, root) to emptyList(),
                    listOf(root, other) to listOf(edge, edge),
                )) {
                assertEquals(
                    CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH,
                    CompleteCallbackForwardingGraph.fromCompiler(
                            root,
                            formals,
                            edges,
                            CallbackInvocationScan.EXHAUSTIVE,
                        )
                        .failure(),
                )
            }
            for (scan in listOf(CallbackInvocationScan.INCOMPLETE, CallbackInvocationScan.NOT_APPLICABLE)) {
                assertEquals(
                    CallbackInvocationFlowFailure.INVALID_SCAN_PROOF,
                    CompleteCallbackForwardingGraph.fromCompiler(root, listOf(root), emptyList(), scan).failure(),
                )
            }
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_SCAN_PROOF,
                CompleteCallbackForwardingGraph.fromCompiler(
                        root,
                        listOf(root),
                        emptyList(),
                        CallbackInvocationScan.EXHAUSTIVE,
                        setOf(CallbackInvocationFlowCause.EXTERNAL_CALLABLE),
                    )
                    .failure(),
            )
        }

    @Test
    fun `graph evidence cannot admit unrecorded paths or another root`(): Unit =
        with(fixture) {
            val root = formal()
            val edge = forward(root, root, 260, 280)
            val terminal =
                CallbackParameterInvocation.fromCompiler(
                        occurrence(320, 330),
                        RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                        forwardings = listOf(edge),
                    )
                    .value()
            val proof = graph(root, listOf(root), emptyList())
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH,
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.Bound(binding),
                        listOf(terminal),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                        CallbackForwardingEvidence.ExhaustedGraph(proof),
                    )
                    .failure(),
            )
            val otherRoot =
                CallbackParameterIdentity.fromCompiler(
                        target,
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(250, 255),
                    )
                    .value()
            val otherProof = graph(otherRoot, listOf(otherRoot), emptyList())
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH,
                CallbackParameterSummary.fromCompiler(
                        root,
                        emptyList(),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                        forwarding = CallbackForwardingEvidence.ExhaustedGraph(otherProof),
                    )
                    .failure(),
            )
        }

    private fun CallbackInvocationFlowFixture.secondBinding() =
        CallbackArgumentBinding.fromCompiler(
                ValueInvocation.fromCompiler(caller, range(100, 180), target).value(),
                supplyingOwner,
                binding.position,
                binding.parameter,
            )
            .value()

    private fun CallbackInvocationFlowFixture.formal() =
        CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()

    private fun CallbackInvocationFlowFixture.forward(
        source: CallbackParameterIdentity,
        target: CallbackParameterIdentity,
        start: Int,
        end: Int,
    ): CallbackParameterForwarding {
        val mapped =
            CallbackArgumentBinding.fromCompiler(
                    ValueInvocation.fromCompiler(source.callable, range(start, end), target.callable).value(),
                    RelationCallableBody.Named.fromCompiler((source.callable as RelationEndpoint.Resolved).evidence)
                        .value(),
                    target.position,
                    target.parameter,
                )
                .value()
        return CallbackParameterForwarding.fromCompiler(source, occurrence(start + 8, start + 13), mapped).value()
    }

    private fun CallbackInvocationFlowFixture.graph(
        root: CallbackParameterIdentity,
        formals: List<CallbackParameterIdentity>,
        edges: List<CallbackParameterForwarding>,
    ) = CompleteCallbackForwardingGraph.fromCompiler(root, formals, edges, CallbackInvocationScan.EXHAUSTIVE).value()

    private fun CallbackInvocationFlowFixture.flow(
        proof: CompleteCallbackForwardingGraph,
        invocations: List<CallbackParameterInvocation>,
    ) =
        CallbackInvocationFlow.fromCompiler(
                lease.identity,
                body,
                CallbackBindingEvidence.Bound(binding),
                invocations,
                emptySet(),
                CallbackInvocationScan.EXHAUSTIVE,
                CallbackForwardingEvidence.ExhaustedGraph(proof),
            )
            .value()

    private fun CallbackInvocationFlowFixture.observation(flow: CallbackInvocationFlow): RelationCallbackObservation {
        val request =
            RelationRequest.start(
                caller,
                RelationMeaning.Callees,
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(50).value(),
                        WorkUnitLimit.parse(500).value(),
                        ElapsedTimeLimitMillis.parse(5000).value(),
                    ),
                    RelationByteLimit.parse(100_000).value(),
                ),
            )
        return RelationCallbackObservation.fromNativeBoundary(
                request,
                occurrence(flow.body.range.startInclusive + 10, flow.body.range.startInclusive + 11),
                endpoint("sink", 610, 700, emptyList()).evidence,
                caller.evidence,
                RelationOccurrence.fromBoundary(
                        flow.body.file,
                        flow.body.range.startInclusive,
                        flow.body.range.endExclusive,
                    )
                    .value(),
                CallbackNamedCallPolicy.Excluded(CallbackExclusionReason.NON_INLINE_ARGUMENT, occurrence(20, 180)),
                CallbackInvocationFlowRead.Observed(flow),
            )
            .value()
    }
}
