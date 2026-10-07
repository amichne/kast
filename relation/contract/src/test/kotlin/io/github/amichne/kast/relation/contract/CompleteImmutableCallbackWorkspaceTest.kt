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
import org.junit.jupiter.api.Test

/** Explicit compiler facts prove graph admission; these cases do not establish native resolution or activation. */
class CompleteImmutableCallbackWorkspaceTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `returned unused value cannot hide its outside workspace factory caller`() =
        with(fixture) {
            val foreign = foreignCaller()
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val origin = ImmutableCallbackValueOrigin.Anonymous(body)
            val returned = ImmutableCallbackValue.fromCompiler(origin, source, source, emptyList()).value()
            val call = ValueInvocation.fromCompiler(foreign, range(20, 80), caller).value()
            val factory = CallbackFactoryReturn.fromCompiler(call, returned, emptyList(), bodyCalls(body)).value()
            val value =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Returned(factory),
                        call.resultSite(),
                        call.resultSite(),
                        emptyList(),
                    )
                    .value()
            val flow =
                ImmutableCallbackInvocationFlow.fromCompiler(
                        origin,
                        source,
                        listOf(ImmutableCallbackInvocationUse.Unused(value)),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val observation =
                RelationCallbackObservation.fromNativeBoundary(
                        request(),
                        occurrence(40, 41),
                        endpoint("sink", 610, 700, emptyList()).evidence,
                        caller.evidence,
                        occurrence(30, 60),
                        CallbackNamedCallPolicy.Excluded(CallbackExclusionReason.RETURNED_CALLBACK, occurrence(20, 80)),
                        CallbackInvocationFlowRead.Immutable(flow),
                    )
                    .value()
            assertEquals(
                StaticCallbackGraphFailure.OutsideWorkspace,
                CompleteStaticCallbackGraph.admit(observation).failure(),
            )
        }

    @Test
    fun `unused named origin still retains its outside workspace target`() =
        with(fixture) {
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val origin =
                ImmutableCallbackValueOrigin.Named(
                    occurrence(30, 60),
                    foreignCaller(),
                    CallbackReferenceReceivers(CallbackReferenceReceiver.Absent, CallbackReferenceReceiver.Absent),
                )
            val flow =
                ImmutableCallbackInvocationFlow.fromCompiler(
                        origin,
                        source,
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            assertEquals(
                StaticCallbackGraphFailure.OutsideWorkspace,
                flow.admitCallbackWorkspace(lease.workspaceRoot).failure(),
            )
        }

    private fun CallbackInvocationFlowFixture.foreignCaller(): RelationEndpoint.Resolved {
        val otherRoot = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/other")).value()
        val otherFile =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(otherRoot, Path.of("/other/Flow.kt")).value()
            )
        val original = endpoint("foreign", 0, 200, emptyList()).evidence
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    otherFile,
                    0,
                    200,
                    "foreign",
                    "fixture.foreign",
                    original.kind,
                    original.signature,
                )
                .value()
        return RelationEndpoint.resolve(lease, caller.scope, evidence).value()
    }

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
