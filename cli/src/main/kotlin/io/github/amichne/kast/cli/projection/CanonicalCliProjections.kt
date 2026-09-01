package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliRequestPreparer
import io.github.amichne.kast.cli.TypedCliProjection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeVerifyRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** The exact generated request preparations consumed by the canonical command graph. */
internal class CanonicalCliRequestPreparers(
    val workspaceInspect: CliRequestPreparer<WorkspaceInspectRequest>,
    val indexSync: CliRequestPreparer<IndexSyncRequest>,
    val topologyBuild: CliRequestPreparer<TopologyBuildRequest>,
    val symbolDiscover: CliRequestPreparer<SymbolDiscoverRequest>,
    val symbolResolve: CliRequestPreparer<SymbolResolveRequest>,
    val symbolDescribe: CliRequestPreparer<SymbolDescribeRequest>,
    val relationRead: CliRequestPreparer<RelationReadRequest>,
    val traversalRun: CliRequestPreparer<TraversalRunRequest>,
    val diagnosticCheck: CliRequestPreparer<DiagnosticCheckRequest>,
    val changePlan: CliRequestPreparer<ChangePlanRequest>,
    val changeApply: CliRequestPreparer<ChangeApplyRequest>,
    val changeVerify: CliRequestPreparer<ChangeVerifyRequest>,
    val changeRecover: CliRequestPreparer<ChangeRecoverRequest>,
)

/** Captures every generated wire binding behind its concrete request type. */
internal fun canonicalCliRequestPreparers(): CanonicalCliRequestPreparers =
    CanonicalCliRequestPreparers(
        workspaceInspect = TypedCliProjection(
            CanonicalOperationWireBindings.workspaceInspect,
            workspaceInspectCliProjector,
        ),
        indexSync = TypedCliProjection(
            CanonicalOperationWireBindings.indexSync,
            indexSyncCliProjector,
        ),
        topologyBuild = TypedCliProjection(
            CanonicalOperationWireBindings.topologyBuild,
            topologyBuildCliProjector,
        ),
        symbolDiscover = TypedCliProjection(
            CanonicalOperationWireBindings.symbolDiscover,
            symbolDiscoverCliProjector,
        ),
        symbolResolve = TypedCliProjection(
            CanonicalOperationWireBindings.symbolResolve,
            symbolResolveCliProjector,
        ),
        symbolDescribe = TypedCliProjection(
            CanonicalOperationWireBindings.symbolDescribe,
            symbolDescribeCliProjector,
        ),
        relationRead = TypedCliProjection(
            CanonicalOperationWireBindings.relationRead,
            relationReadCliProjector,
        ),
        traversalRun = TypedCliProjection(
            CanonicalOperationWireBindings.traversalRun,
            traversalRunCliProjector,
        ),
        diagnosticCheck = TypedCliProjection(
            CanonicalOperationWireBindings.diagnosticCheck,
            diagnosticCheckCliProjector,
        ),
        changePlan = TypedCliProjection(
            CanonicalOperationWireBindings.changePlan,
            changePlanCliProjector,
        ),
        changeApply = TypedCliProjection(
            CanonicalOperationWireBindings.changeApply,
            changeApplyCliProjector,
        ),
        changeVerify = TypedCliProjection(
            CanonicalOperationWireBindings.changeVerify,
            changeVerifyCliProjector,
        ),
        changeRecover = TypedCliProjection(
            CanonicalOperationWireBindings.changeRecover,
            changeRecoverCliProjector,
        ),
    )
