package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.apply.AddDeclarationApplyOperations
import io.github.amichne.kast.change.verify.VerifiedMutationOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeVerifyQualification
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyRequest
import io.github.amichne.kast.protocol.contract.ChangeVerifyResult
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SymbolDescribeQualification
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolResolveQualification
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildResult
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.IndexSynchronizationOperations
import io.github.amichne.kast.protocol.contract.IndexSyncQualification
import io.github.amichne.kast.protocol.contract.IndexSyncRejection
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.contract.IndexSyncResult

/**
 * Operation-specific protocol projection boundary for the fourteen target service associations.
 *
 * Implementations parse public boundary documents into the supplied strong service contracts and
 * project their closed results back to protocol outcomes. Canonical definitions, serializers, and
 * typed bindings remain owned and constructed by runtime composition.
 */
interface KastOperationHandlerFactory {
    fun workspaceInspect(
        operations: WorkspaceInspectionOperations,
    ): OperationHandler<
        WorkspaceInspectRequest,
        WorkspaceInspectResult,
        WorkspaceInspectQualification,
        WorkspaceInspectRejection
        >

    fun indexSync(
        operations: IndexSynchronizationOperations,
    ): OperationHandler<IndexSyncRequest, IndexSyncResult, IndexSyncQualification, IndexSyncRejection>

    fun topologyBuild(
        operations: TopologyBuildOperations,
    ): OperationHandler<
        TopologyBuildRequest,
        TopologyBuildResult,
        TopologyBuildQualification,
        TopologyBuildRejection
        >

    fun symbolDiscover(
        operations: SymbolDiscoveryOperations,
    ): OperationHandler<
        SymbolDiscoverRequest,
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection
        >

    fun symbolResolve(
        operations: SymbolExactOperations,
    ): OperationHandler<
        SymbolResolveRequest,
        SymbolResolveResult,
        SymbolResolveQualification,
        SymbolResolveRejection
        >

    fun symbolDescribe(
        operations: SymbolExactOperations,
    ): OperationHandler<
        SymbolDescribeRequest,
        SymbolDescribeResult,
        SymbolDescribeQualification,
        SymbolDescribeRejection
        >

    fun sourceRead(
        operations: SourceReadOperations,
    ): OperationHandler<
        SourceReadRequest,
        SourceReadResult,
        SourceReadQualification,
        SourceReadRejection
        >

    fun relationRead(
        operations: RelationOperations,
    ): OperationHandler<
        RelationReadRequest,
        RelationReadResult,
        RelationReadQualification,
        RelationReadRejection
        >

    fun traversalRun(
        operations: TraversalOperations,
    ): OperationHandler<
        TraversalRunRequest,
        TraversalRunResult,
        TraversalRunQualification,
        TraversalRunRejection
        >

    fun diagnosticCheck(
        operations: DiagnosticOperations,
    ): OperationHandler<
        DiagnosticCheckRequest,
        DiagnosticCheckResult,
        DiagnosticCheckQualification,
        DiagnosticCheckRejection
        >

    fun changePlan(
        operations: ChangePlanningOperations,
    ): OperationHandler<
        ChangePlanRequest,
        ChangePlanResult,
        ChangePlanQualification,
        ChangePlanRejection
        >

    fun changeApply(
        operations: AddDeclarationApplyOperations,
    ): OperationHandler<
        ChangeApplyRequest,
        ChangeApplyResult,
        ChangeApplyQualification,
        ChangeApplyRejection
        >

    fun changeVerify(
        operations: VerifiedMutationOperations,
    ): OperationHandler<
        ChangeVerifyRequest,
        ChangeVerifyResult,
        ChangeVerifyQualification,
        ChangeVerifyRejection
        >

    fun changeRecover(
        operations: ChangeRecoveryOperations,
    ): OperationHandler<
        ChangeRecoverRequest,
        ChangeRecoverResult,
        ChangeRecoverQualification,
        ChangeRecoverRejection
        >
}
