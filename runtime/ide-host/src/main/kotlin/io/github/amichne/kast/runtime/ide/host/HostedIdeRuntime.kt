package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.runtime.ide.read.dispatch.IdeReadRuntimeDispatchFailure
import io.github.amichne.kast.runtime.ide.read.dispatch.IdeReadRuntimeDispatchResult
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntime
import io.github.amichne.kast.runtime.server.RuntimeServer
import io.github.amichne.kast.runtime.server.RuntimeServerConstruction
import io.github.amichne.kast.runtime.server.ServerDispatch
import io.github.amichne.kast.change.verify.DurableChangeAuthority
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.runtime.server.TypedOperationBinding
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.protocol.contract.RelationReadRejection

sealed interface HostedIdeRuntimeConstruction {
    data class Created(val runtime: HostedIdeRuntime) : HostedIdeRuntimeConstruction
    data class Rejected(
        val construction: RuntimeServerConstruction.Rejected,
    ) : HostedIdeRuntimeConstruction
}

sealed interface HostedIdeRuntimeDispatch {
    data class Responded(val document: String) : HostedIdeRuntimeDispatch
    data object Rejected : HostedIdeRuntimeDispatch
}

/** Existing exact-four read runtime plus the exact generated hosted-effects table. */
class HostedIdeRuntime private constructor(
    private val reads: HostedReadRuntimeOperations,
    private val effects: RuntimeServer,
) {
    val canonicalRoot get() = reads.canonicalRoot
    val compatibility get() = reads.compatibility

    suspend fun dispatch(document: String): HostedIdeRuntimeDispatch = when (
        val read = reads.dispatch(document)
    ) {
        is IdeReadRuntimeDispatchResult.Responded ->
            HostedIdeRuntimeDispatch.Responded(read.document)
        is IdeReadRuntimeDispatchResult.Rejected -> when (read.failure) {
            is IdeReadRuntimeDispatchFailure.UnsupportedOperation -> when (
                val effect = effects.dispatch(document)
            ) {
                is ServerDispatch.Responded -> HostedIdeRuntimeDispatch.Responded(effect.document)
                is ServerDispatch.Rejected -> HostedIdeRuntimeDispatch.Rejected
            }
            is IdeReadRuntimeDispatchFailure.RequestAdmissionFailed,
            is IdeReadRuntimeDispatchFailure.RequestDecodingFailed,
            is IdeReadRuntimeDispatchFailure.ResponseEncodingFailed,
            IdeReadRuntimeDispatchFailure.RuntimeGenerationUnavailable,
            -> HostedIdeRuntimeDispatch.Rejected
        }
    }

    companion object {
        internal fun create(
            reads: HostedReadRuntimeOperations,
            workspace: HostedWorkspaceOperations,
            topology: HostedTopologyOperations,
            selectors: HostedExactSelectorOperations,
            relations: RelationOperations,
            diagnostics: DiagnosticOperations,
            mutation: HostedMutationState,
            mutationAdmission: HostedMutationAdmissionOperations,
            authority: DurableChangeAuthority,
        ): HostedIdeRuntimeConstruction = when (val server = RuntimeServer.createHostedEffects(
            HostedTopologyProtocol.bindings(topology, selectors, relations) +
                HostedDiagnosticProtocol.bindings(workspace, diagnostics) +
                HostedMutationProtocol.bindings(mutation, mutationAdmission, selectors, authority),
        )) {
            is RuntimeServerConstruction.Created -> HostedIdeRuntimeConstruction.Created(
                HostedIdeRuntime(reads, server.server),
            )
            is RuntimeServerConstruction.Rejected -> HostedIdeRuntimeConstruction.Rejected(server)
        }

        /** Unit-test fixture retaining a complete rejecting effect table behind a real read runtime. */
        internal fun testing(reads: HostedIdeReadRuntime): HostedIdeRuntime = when (
            val server = RuntimeServer.createHostedEffects(
                listOf(
                    TypedOperationBinding(
                        CanonicalOperationWireBindings.topologyBuild,
                        OperationHandler { OperationOutcome.Rejected(TopologyBuildRejection.WorkspaceNotReady) },
                    ),
                    TypedOperationBinding(
                        CanonicalOperationWireBindings.relationRead,
                        OperationHandler { OperationOutcome.Rejected(RelationReadRejection.WORKSPACE_NOT_READY) },
                    ),
                    TypedOperationBinding(
                        CanonicalOperationWireBindings.traversalRun,
                        OperationHandler { OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED) },
                    ),
                    TypedOperationBinding(
                        CanonicalOperationWireBindings.diagnosticCheck,
                        OperationHandler { OperationOutcome.Rejected(DiagnosticCheckRejection.WORKSPACE_NOT_READY) },
                    ),
                    TypedOperationBinding(
                        CanonicalOperationWireBindings.changePlan,
                        OperationHandler { OperationOutcome.Rejected(ChangePlanRejection.WORKSPACE_NOT_READY) },
                    ),
                    TypedOperationBinding(
                        CanonicalOperationWireBindings.changeApply,
                        OperationHandler { OperationOutcome.Rejected(ChangeApplyRejection.PLAN_NOT_FOUND) },
                    ),
                    TypedOperationBinding(
                        CanonicalOperationWireBindings.changeVerify,
                        OperationHandler { OperationOutcome.Rejected(ChangeVerifyRejection.APPLICATION_NOT_FOUND) },
                    ),
                    TypedOperationBinding(
                        CanonicalOperationWireBindings.changeRecover,
                        OperationHandler { OperationOutcome.Rejected(ChangeRecoverRejection.PLAN_NOT_FOUND) },
                    ),
                ),
            )
        ) {
            is RuntimeServerConstruction.Created -> HostedIdeRuntime(
                StaticHostedReadRuntimeOperations(reads),
                server.server,
            )
            is RuntimeServerConstruction.Rejected -> error("test effect table is complete")
        }
    }
}
