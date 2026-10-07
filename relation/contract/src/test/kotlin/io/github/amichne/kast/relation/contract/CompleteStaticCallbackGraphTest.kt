package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Explicit compiler facts prove graph admission; these cases do not establish native resolution or activation. */
class CompleteStaticCallbackGraphTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `bound callback graph retains supply invocation and body target without lexical named edge`(): Unit =
        with(fixture) {
            val terminal =
                CallbackParameterInvocation.fromCompiler(
                        occurrence(320, 330),
                        RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                    )
                    .value()
            val flow = exhaustive(binding, body, listOf(terminal))
            val observation = observation(flow)
            val graph = CompleteStaticCallbackGraph.admit(observation).value()
            assertSame(observation, graph.observation)
            assertEquals(lease, graph.basis)
            assertEquals(
                listOf(
                    StaticCallbackEdge.Supply::class,
                    StaticCallbackEdge.Invoke::class,
                    StaticCallbackEdge.BodyTarget::class,
                ),
                graph.edges.map { it::class },
            )
            val supply = graph.edges[0] as StaticCallbackEdge.Supply
            val invoke = graph.edges[1] as StaticCallbackEdge.Invoke
            val bodyTarget = graph.edges[2] as StaticCallbackEdge.BodyTarget
            assertEquals(binding, supply.binding)
            assertEquals(supply.target, invoke.source)
            assertEquals(body, invoke.target.body)
            assertEquals(target.evidence, invoke.owner.callable.evidence)
            assertEquals(body, bodyTarget.source.body)
            assertEquals(observation.target, bodyTarget.target.callable.evidence)
            assertEquals(observation.policy, bodyTarget.namedCallPolicy)
            assertTrue(bodyTarget.namedCallPolicy is CallbackNamedCallPolicy.Excluded)
        }

    @Test
    fun `forwarding graph preserves contextual formals and the independent call site bindings`(): Unit =
        with(fixture) {
            val receiver = endpoint("forward", 410, 500, listOf("()->Unit"))
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val mapped =
                CallbackArgumentBinding.fromCompiler(
                        ValueInvocation.fromCompiler(target, range(260, 280), receiver).value(),
                        RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(425, 445),
                    )
                    .value()
            val forwarding = CallbackParameterForwarding.fromCompiler(formal, occurrence(268, 273), mapped).value()
            val terminal =
                CallbackParameterInvocation.fromCompiler(
                        occurrence(470, 480),
                        RelationCallableBody.Named.fromCompiler(receiver.evidence).value(),
                        forwardings = listOf(forwarding),
                    )
                    .value()
            val first =
                CompleteStaticCallbackGraph.admit(observation(exhaustive(binding, body, listOf(terminal)))).value()
            val otherBinding =
                CallbackArgumentBinding.fromCompiler(
                        ValueInvocation.fromCompiler(caller, range(100, 180), target).value(),
                        supplyingOwner,
                        binding.position,
                        binding.parameter,
                    )
                    .value()
            val otherBody = anonymous(120, 150)
            val second =
                CompleteStaticCallbackGraph.admit(
                        observation(exhaustive(otherBinding, otherBody, listOf(terminal)), callOffset = 130)
                    )
                    .value()
            val firstForward = first.edges.filterIsInstance<StaticCallbackEdge.Forward>().single()
            val secondForward = second.edges.filterIsInstance<StaticCallbackEdge.Forward>().single()
            assertEquals(forwarding, firstForward.evidence)
            assertEquals(firstForward.source.parameter, secondForward.source.parameter)
            assertEquals(firstForward.target.parameter, secondForward.target.parameter)
            assertNotEquals(firstForward.source, secondForward.source)
            assertNotEquals(firstForward.target, secondForward.target)
            assertEquals(binding, firstForward.target.supply)
            assertEquals(otherBinding, secondForward.target.supply)
            assertEquals(otherBody, second.edges.filterIsInstance<StaticCallbackEdge.Invoke>().single().target.body)
        }

    @Test
    fun `direct graph uses actual named invoke owner and unused formal invents no invocation`(): Unit =
        with(fixture) {
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
            val graph =
                CompleteStaticCallbackGraph.admit(observation(flow, policy = CallbackNamedCallPolicy.AdmittedDirect))
                    .value()
            assertEquals(direct, graph.edges.filterIsInstance<StaticCallbackEdge.DirectInvoke>().single().evidence)
            assertTrue(graph.nodes.none { it is StaticCallbackNode.Formal })
            val unused = CompleteStaticCallbackGraph.admit(observation(exhaustive(binding, body, emptyList()))).value()
            assertEquals(2, unused.edges.size)
            assertTrue(unused.edges.none { it is StaticCallbackEdge.Invoke || it is StaticCallbackEdge.DirectInvoke })
        }

    @Test
    fun `incomplete unresolved and unavailable evidence cannot become a complete graph`(): Unit =
        with(fixture) {
            val unresolved =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.Bound(binding),
                        listOf(nested),
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val rejected =
                CompleteStaticCallbackGraph.admit(observation(unresolved)).failure()
                    as StaticCallbackGraphFailure.Unresolved
            assertSame(unresolved, rejected.flow)
            val terminal = CallbackParameterInvocation.fromCompiler(occurrence(320, 330), supplyingTarget()).value()
            val incomplete =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.Bound(binding),
                        listOf(terminal),
                        emptySet(),
                        CallbackInvocationScan.INCOMPLETE,
                    )
                    .value()
            assertEquals(
                StaticCallbackGraphFailure.IncompleteScan,
                CompleteStaticCallbackGraph.admit(observation(incomplete)).failure(),
            )
            val unavailable =
                RelationCallbackObservation.fromNativeBoundary(
                        request(),
                        occurrence(40, 41),
                        endpoint("sink", 610, 700, emptyList()).evidence,
                        caller.evidence,
                        occurrence(30, 60),
                        CallbackNamedCallPolicy.Excluded(
                            CallbackExclusionReason.NON_INLINE_ARGUMENT,
                            occurrence(20, 80),
                        ),
                        CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.EXTERNAL_CALLABLE),
                    )
                    .value()
            assertEquals(
                StaticCallbackGraphFailure.Unavailable(CallbackInvocationFlowCause.EXTERNAL_CALLABLE),
                CompleteStaticCallbackGraph.admit(unavailable).failure(),
            )
        }

    @Test
    fun `recursive formal route requires cycle proof instead of trusting an empty obligation set`(): Unit =
        with(fixture) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val recursive =
                CallbackArgumentBinding.fromCompiler(
                        ValueInvocation.fromCompiler(target, range(260, 280), target).value(),
                        supplyingTarget(),
                        binding.position,
                        binding.parameter,
                    )
                    .value()
            val forwarding = CallbackParameterForwarding.fromCompiler(formal, occurrence(268, 273), recursive).value()
            val terminal =
                CallbackParameterInvocation.fromCompiler(
                        occurrence(320, 330),
                        supplyingTarget(),
                        forwardings = listOf(forwarding),
                    )
                    .value()
            val flow = exhaustive(binding, body, listOf(terminal))
            assertTrue(flow.obligations.isEmpty())
            assertEquals(
                StaticCallbackGraphFailure.CyclicRoute,
                CompleteStaticCallbackGraph.admit(observation(flow)).failure(),
            )
        }

    @Test
    fun `workspace tag from another root cannot admit a graph outside this authority`(): Unit =
        with(fixture) {
            val otherRoot = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/other")).value()
            val otherFile =
                SymbolDiscoveryFileIdentity.Workspace(
                    CanonicalWorkspaceFilePath.fromCanonicalPath(otherRoot, Path.of("/other/Flow.kt")).value()
                )
            val sink = endpoint("sink", 610, 700, emptyList()).evidence
            val outside =
                CompilerGroundedSymbolEvidence.fromBoundary(
                        otherFile,
                        610,
                        700,
                        "sink",
                        "fixture.sink",
                        sink.kind,
                        sink.signature,
                    )
                    .value()
            val observation =
                RelationCallbackObservation.fromNativeBoundary(
                        request(),
                        occurrence(40, 41),
                        outside,
                        caller.evidence,
                        occurrence(30, 60),
                        CallbackNamedCallPolicy.Excluded(
                            CallbackExclusionReason.NON_INLINE_ARGUMENT,
                            occurrence(20, 80),
                        ),
                        CallbackInvocationFlowRead.Observed(exhaustive(binding, body, emptyList())),
                    )
                    .value()
            assertEquals(
                StaticCallbackGraphFailure.OutsideWorkspace,
                CompleteStaticCallbackGraph.admit(observation).failure(),
            )
        }

    private fun CallbackInvocationFlowFixture.supplyingTarget() =
        RelationCallableBody.Named.fromCompiler(target.evidence).value()

    private fun CallbackInvocationFlowFixture.exhaustive(
        binding: CallbackArgumentBinding,
        body: RelationCallableBody.Anonymous,
        invocations: List<CallbackParameterInvocation>,
    ) =
        CallbackInvocationFlow.fromCompiler(
                lease.identity,
                body,
                CallbackBindingEvidence.Bound(binding),
                invocations,
                emptySet(),
                CallbackInvocationScan.EXHAUSTIVE,
            )
            .value()

    private fun CallbackInvocationFlowFixture.observation(
        flow: CallbackInvocationFlow,
        callOffset: Int = 40,
        policy: CallbackNamedCallPolicy =
            CallbackNamedCallPolicy.Excluded(
                CallbackExclusionReason.NON_INLINE_ARGUMENT,
                occurrence(20, 180),
            ),
    ) =
        RelationCallbackObservation.fromNativeBoundary(
                request(),
                occurrence(callOffset, callOffset + 1),
                endpoint("sink", 610, 700, emptyList()).evidence,
                caller.evidence,
                RelationOccurrence.fromBoundary(
                        flow.body.file,
                        flow.body.range.startInclusive,
                        flow.body.range.endExclusive,
                    )
                    .value(),
                policy,
                CallbackInvocationFlowRead.Observed(flow),
            )
            .value()

    private fun CallbackInvocationFlowFixture.request() =
        RelationRequest.start(
            caller,
            RelationMeaning.Callees,
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(32).value(),
                    WorkUnitLimit.parse(100).value(),
                    ElapsedTimeLimitMillis.parse(1000).value(),
                ),
                RelationByteLimit.parse(100_000).value(),
            ),
        )
}
