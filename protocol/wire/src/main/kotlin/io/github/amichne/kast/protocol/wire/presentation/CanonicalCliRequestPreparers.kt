package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** The exact generated request preparations consumed by the canonical command graph. */
class CanonicalCliRequestPreparers(
    val indexSync: OperationRequestPreparer<IndexSyncRequest>,
    val topologyBuild: OperationRequestPreparer<TopologyBuildRequest>,
    val sourceRead: OperationRequestPreparer<SourceReadRequest>,
    val queryRun: OperationRequestPreparer<QueryRunRequest>,
    val diagnosticCheck: OperationRequestPreparer<DiagnosticCheckRequest>,
    val changePlan: OperationRequestPreparer<ChangePlanRequest>,
    val changeApply: OperationRequestPreparer<ChangeApplyRequest>,
    val changeRecover: OperationRequestPreparer<ChangeRecoverRequest>,
)

/** Captures every generated wire binding behind its concrete request type. */
fun canonicalCliRequestPreparers(): CanonicalCliRequestPreparers =
    CanonicalCliRequestPreparers(
        indexSync = TypedOperationProjection(CanonicalOperationWireBindings.indexSync, indexSyncCliProjector),
        topologyBuild =
            TypedOperationProjection(CanonicalOperationWireBindings.topologyBuild, topologyBuildCliProjector),
        sourceRead = TypedOperationProjection(CanonicalOperationWireBindings.sourceRead, sourceReadCliProjector),
        queryRun = TypedOperationProjection(CanonicalOperationWireBindings.queryRun, queryRunCliProjector),
        diagnosticCheck =
            TypedOperationProjection(CanonicalOperationWireBindings.diagnosticCheck, diagnosticCheckCliProjector),
        changePlan = TypedOperationProjection(CanonicalOperationWireBindings.changePlan, changePlanCliProjector),
        changeApply = TypedOperationProjection(CanonicalOperationWireBindings.changeApply, changeApplyCliProjector),
        changeRecover =
            TypedOperationProjection(CanonicalOperationWireBindings.changeRecover, changeRecoverCliProjector),
    )
