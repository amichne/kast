package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.change.apply.AddDeclarationApplyOperations
import io.github.amichne.kast.change.verify.VerifiedMutationOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.runtime.composition.ChangePlanningOperations
import io.github.amichne.kast.runtime.composition.ChangeRecoveryOperations
import io.github.amichne.kast.runtime.composition.InstalledWorkspaceRoot
import io.github.amichne.kast.runtime.composition.KastOperationHandlerFactory
import io.github.amichne.kast.runtime.composition.protocol.graph.CanonicalRelationReadHandler
import io.github.amichne.kast.runtime.composition.protocol.graph.CanonicalTopologyBuildHandler
import io.github.amichne.kast.runtime.composition.protocol.graph.CanonicalTraversalRunHandler
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.IndexSynchronizationOperations

/** Canonical operation handlers sharing exact selector and change-transition authorities. */
internal class CanonicalKastOperationHandlerFactory private constructor(
    private val workspaceHandler: CanonicalWorkspaceInspectHandler,
    private val workspace: WorkspaceInspectionOperations,
    private val changeAdmission: ChangePlanAdmissionOperations,
    private val protocolAuthority: CanonicalProtocolAuthority,
    private val changeAuthority: CanonicalChangeAuthority,
) : KastOperationHandlerFactory {
    override fun workspaceInspect(
        operations: WorkspaceInspectionOperations,
    ): CanonicalWorkspaceInspectHandler = workspaceHandler

    override fun indexSync(
        operations: IndexSynchronizationOperations,
    ) = CanonicalIndexSyncHandler(operations)

    override fun topologyBuild(
        operations: TopologyBuildOperations,
    ) = CanonicalTopologyBuildHandler(operations)

    override fun symbolDiscover(
        operations: SymbolDiscoveryOperations,
    ) = CanonicalSymbolDiscoverHandler(workspace, operations, protocolAuthority)

    override fun symbolResolve(
        operations: SymbolExactOperations,
    ) = CanonicalSymbolResolveHandler(operations, protocolAuthority)

    override fun symbolDescribe(
        operations: SymbolExactOperations,
    ) = CanonicalSymbolDescribeHandler(operations, protocolAuthority)

    override fun relationRead(
        operations: RelationOperations,
    ) = CanonicalRelationReadHandler(operations, protocolAuthority)

    override fun traversalRun(
        operations: TraversalOperations,
    ) = CanonicalTraversalRunHandler(operations, protocolAuthority)

    override fun diagnosticCheck(
        operations: DiagnosticOperations,
    ) = CanonicalDiagnosticCheckHandler(workspace, operations)

    override fun changePlan(
        operations: ChangePlanningOperations,
    ) = CanonicalChangePlanHandler(
        operations,
        changeAdmission,
        protocolAuthority,
        changeAuthority,
    )

    override fun changeApply(
        operations: AddDeclarationApplyOperations,
    ) = CanonicalChangeApplyHandler(workspace, operations, changeAuthority)

    override fun changeVerify(
        operations: VerifiedMutationOperations,
    ) = CanonicalChangeVerifyHandler(operations, changeAuthority)

    override fun changeRecover(
        operations: ChangeRecoveryOperations,
    ) = CanonicalChangeRecoverHandler(operations, changeAuthority)

    companion object {
        /**
         * Proof transition: `(InstalledWorkspaceRoot, WorkspaceInspectionOperations,
         * ChangePlanAdmissionOperations) -> Refinement<CanonicalKastOperationHandlerFactory,
         * WorkspaceInspectHandlerConstructionFailure>`.
         *
         * Establishes all thirteen canonical handlers under one exact installed root, one selector
         * authority, and one plan/apply/verify authority. The closed construction failure preserves
         * an unrepresentable root. Raw root extraction remains confined to workspace projection.
         */
        fun create(
            root: InstalledWorkspaceRoot,
            workspace: WorkspaceInspectionOperations,
            changeAdmission: ChangePlanAdmissionOperations,
        ): Refinement<
            CanonicalKastOperationHandlerFactory,
            WorkspaceInspectHandlerConstructionFailure,
            > = when (val handler = CanonicalWorkspaceInspectHandler.create(root, workspace)) {
            is Refinement.Refined -> Refinement.Refined(
                CanonicalKastOperationHandlerFactory(
                    handler.value,
                    workspace,
                    changeAdmission,
                    CanonicalProtocolAuthority(),
                    CanonicalChangeAuthority(),
                ),
            )
            is Refinement.Rejected -> Refinement.Rejected(handler.failure)
        }
    }
}
