package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Explicit compiler facts prove graph admission; these cases do not establish native resolution or activation. */
class DependencyStaticCallbackGraphTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `dependency contract graph preserves provenance and excludes dependency nodes`(): Unit =
        with(fixture) {
            val external =
                CompilerGroundedSymbolEvidence.fromBoundary(
                        SymbolDiscoveryFileIdentity.External(
                            io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl.parse(
                                    "jar:///stdlib.jar!/kotlin/StandardKt.class"
                                )
                                .value()
                        ),
                        210,
                        400,
                        target.evidence.name.value,
                        "fixture.nativeBoundary",
                        target.evidence.kind,
                        target.evidence.signature,
                    )
                    .value()
            val binding =
                CallbackDependencyContract.fromCompiler(
                        lease.identity,
                        occurrence(20, 80),
                        supplyingOwner,
                        external,
                        ValueArgumentPosition.parse(1).value(),
                        DependencyClassDigest.parse("a".repeat(64)).value(),
                        CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                        CallbackDependencyInvocationKind.EXACTLY_ONCE,
                    )
                    .value()
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.DependencyContract(binding),
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val graph = CompleteStaticCallbackGraph.admit(observation(flow)).value()
            assertEquals(
                listOf(StaticCallbackEdge.DependencyContractInvoke::class, StaticCallbackEdge.BodyTarget::class),
                graph.edges.map { it::class },
            )
            assertSame(binding, (graph.edges.first() as StaticCallbackEdge.DependencyContractInvoke).evidence)
            assertEquals(3, graph.nodes.size)
            assertTrue(graph.nodes.none { it is StaticCallbackNode.Formal })
            assertEquals(body, (graph.edges.last() as StaticCallbackEdge.BodyTarget).source.body)
        }

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
