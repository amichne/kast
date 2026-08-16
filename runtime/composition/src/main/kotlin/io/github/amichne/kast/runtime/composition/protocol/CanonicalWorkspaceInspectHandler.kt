package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument
import io.github.amichne.kast.runtime.composition.InstalledWorkspaceRoot
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Finite failures that prevent exact-root workspace inspection projection. */
enum class WorkspaceInspectHandlerConstructionFailure {
    ROOT_UNREPRESENTABLE,
}

/** Canonical protocol projection over one exact installed workspace inspection operation. */
class CanonicalWorkspaceInspectHandler private constructor(
    private val root: ProtocolText,
    private val operations: WorkspaceInspectionOperations,
) : OperationHandler<
    WorkspaceInspectRequest,
    WorkspaceInspectResult,
    WorkspaceInspectQualification,
    WorkspaceInspectRejection,
    > {
    override suspend fun execute(
        request: WorkspaceInspectRequest,
    ): OperationOutcome<
        WorkspaceInspectResult,
        WorkspaceInspectQualification,
        WorkspaceInspectRejection,
        > = when (val state = operations.inspect()) {
        is WorkspaceRuntimeState.Ready -> if (state.workspace.root.value == root.value) {
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperation.WORKSPACE_INSPECT.id,
                    state.workspace.generation,
                    WorkspaceInspectResult(root, WorkspaceStateDocument.READY),
                ),
            )
        } else {
            OperationOutcome.Rejected(WorkspaceInspectRejection.ROOT_UNAVAILABLE)
        }
        is WorkspaceRuntimeState.Blocked ->
            OperationOutcome.Rejected(WorkspaceInspectRejection.RUNTIME_BLOCKED)
        WorkspaceRuntimeState.Absent,
        WorkspaceRuntimeState.Starting,
        WorkspaceRuntimeState.Reconciling,
        WorkspaceRuntimeState.Stopping,
            -> OperationOutcome.Rejected(WorkspaceInspectRejection.ROOT_UNAVAILABLE)
    }

    companion object {
        /**
         * Proof transition: `(InstalledWorkspaceRoot, WorkspaceInspectionOperations) ->
         * Refinement<CanonicalWorkspaceInspectHandler,
         * WorkspaceInspectHandlerConstructionFailure>`.
         *
         * Establishes a bounded protocol root permanently equal to the physically admitted
         * installed root. [WorkspaceInspectHandlerConstructionFailure] is the closed expected
         * failure. Raw root text may leave the stronger installed root only at this protocol
         * projection boundary.
         */
        fun create(
            root: InstalledWorkspaceRoot,
            operations: WorkspaceInspectionOperations,
        ): Refinement<
            CanonicalWorkspaceInspectHandler,
            WorkspaceInspectHandlerConstructionFailure,
            > = when (val protocolRoot = ProtocolText.parse(root.path.toString())) {
            is Refinement.Refined -> Refinement.Refined(
                CanonicalWorkspaceInspectHandler(protocolRoot.value, operations),
            )
            is Refinement.Rejected -> Refinement.Rejected(
                WorkspaceInspectHandlerConstructionFailure.ROOT_UNREPRESENTABLE,
            )
        }
    }
}
