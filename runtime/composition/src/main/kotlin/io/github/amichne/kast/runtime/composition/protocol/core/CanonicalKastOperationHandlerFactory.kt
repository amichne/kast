package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.runtime.composition.ChangePlanningOperations
import io.github.amichne.kast.runtime.composition.ChangeRecoveryOperations
import io.github.amichne.kast.runtime.composition.KastOperationHandlerFactory
import io.github.amichne.kast.runtime.composition.VerifiedChangeApplyOperations
import io.github.amichne.kast.runtime.composition.protocol.graph.CanonicalRelationReadHandler
import io.github.amichne.kast.runtime.composition.protocol.graph.CanonicalTopologyBuildHandler
import io.github.amichne.kast.runtime.composition.protocol.graph.CanonicalTraversalRunHandler
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.IndexSynchronizationOperations

/** Canonical operation handlers sharing exact selector and change-transition authorities. */
internal class CanonicalKastOperationHandlerFactory private constructor(
    private val workspace: WorkspaceInspectionOperations,
    private val changeAdmission: ChangePlanAdmissionOperations,
    private val protocolAuthority: CanonicalProtocolAuthority,
    private val changeAuthority: CanonicalChangeAuthority,
) : KastOperationHandlerFactory {
    override fun indexSync(
        operations: IndexSynchronizationOperations,
    ) = CanonicalIndexSyncHandler(operations)

    override fun topologyBuild(
        operations: TopologyBuildOperations,
    ) = CanonicalTopologyBuildHandler(operations)

    override fun symbolDiscover(
        operations: SymbolDiscoveryOperations,
    ) = CanonicalSymbolDiscoverHandler(workspace, operations, protocolAuthority)

    override fun symbolInspect(
        operations: SymbolExactOperations,
    ) = CanonicalSymbolInspectHandler(operations, protocolAuthority)

    override fun sourceRead(
        operations: SourceReadOperations,
    ) = CanonicalSourceReadHandler(operations, protocolAuthority)

    override fun relationRead(
        operations: RelationOperations,
    ) = CanonicalRelationReadHandler(operations, protocolAuthority)

    override fun traversalRun(
        operations: TraversalOperations,
    ) = CanonicalTraversalRunHandler(operations, protocolAuthority)

    override fun diagnosticCheck(
        operations: DiagnosticOperations,
    ) = CanonicalDiagnosticCheckHandler(workspace, operations, protocolAuthority)

    override fun changePlan(
        operations: ChangePlanningOperations,
    ) = CanonicalChangePlanHandler(
        operations,
        changeAdmission,
        protocolAuthority,
        changeAuthority,
    )

    override fun changeApply(
        operations: VerifiedChangeApplyOperations,
    ) = CanonicalChangeApplyHandler(workspace, operations, changeAuthority)

    override fun changeRecover(
        operations: ChangeRecoveryOperations,
    ) = CanonicalChangeRecoverHandler(operations, changeAuthority)

    companion object {
        /**
         * Proof transition: `(WorkspaceInspectionOperations,
         * ChangePlanAdmissionOperations) -> CanonicalKastOperationHandlerFactory`.
         *
         * Establishes all eleven canonical handlers under one workspace authority, one selector
         * authority, and one plan/apply/verify authority.
         */
        fun create(
            workspace: WorkspaceInspectionOperations,
            changeAdmission: ChangePlanAdmissionOperations,
        ): CanonicalKastOperationHandlerFactory = CanonicalKastOperationHandlerFactory(
            workspace,
            changeAdmission,
            CanonicalProtocolAuthority(),
            CanonicalChangeAuthority(),
        )
    }
}
