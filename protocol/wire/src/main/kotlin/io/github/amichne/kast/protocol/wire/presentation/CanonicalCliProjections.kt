package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** The exact generated request preparations consumed by the canonical command graph. */
class CanonicalCliRequestPreparers(
    val indexSync: OperationRequestPreparer<IndexSyncRequest>,
    val topologyBuild: OperationRequestPreparer<TopologyBuildRequest>,
    val symbolDiscover: OperationRequestPreparer<SymbolDiscoverRequest>,
    val symbolInspect: OperationRequestPreparer<SymbolInspectRequest>,
    val sourceRead: OperationRequestPreparer<SourceReadRequest>,
    val relationRead: OperationRequestPreparer<RelationReadRequest>,
    val traversalRun: OperationRequestPreparer<TraversalRunRequest>,
    val queryRun: OperationRequestPreparer<QueryRunRequest>,
    val diagnosticCheck: OperationRequestPreparer<DiagnosticCheckRequest>,
    val changePlan: OperationRequestPreparer<ChangePlanRequest>,
    val changeApply: OperationRequestPreparer<ChangeApplyRequest>,
    val changeRecover: OperationRequestPreparer<ChangeRecoverRequest>,
)

/** Captures every generated wire binding behind its concrete request type. */
fun canonicalCliRequestPreparers(): CanonicalCliRequestPreparers =
    CanonicalCliRequestPreparers(
        indexSync =
            TypedOperationProjection(
                CanonicalOperationWireBindings.indexSync,
                indexSyncCliProjector,
            ),
        topologyBuild =
            TypedOperationProjection(
                CanonicalOperationWireBindings.topologyBuild,
                topologyBuildCliProjector,
            ),
        symbolDiscover =
            TypedOperationProjection(
                CanonicalOperationWireBindings.symbolDiscover,
                symbolDiscoverCliProjector,
            ),
        symbolInspect =
            TypedOperationProjection(
                CanonicalOperationWireBindings.symbolInspect,
                symbolInspectCliProjector,
            ),
        sourceRead =
            TypedOperationProjection(
                CanonicalOperationWireBindings.sourceRead,
                sourceReadCliProjector,
            ),
        relationRead =
            TypedOperationProjection(
                CanonicalOperationWireBindings.relationRead,
                relationReadCliProjector,
            ),
        traversalRun =
            TypedOperationProjection(
                CanonicalOperationWireBindings.traversalRun,
                traversalRunCliProjector,
            ),
        queryRun =
            TypedOperationProjection(
                CanonicalOperationWireBindings.queryRun,
                queryRunCliProjector,
            ),
        diagnosticCheck =
            TypedOperationProjection(
                CanonicalOperationWireBindings.diagnosticCheck,
                diagnosticCheckCliProjector,
            ),
        changePlan =
            TypedOperationProjection(
                CanonicalOperationWireBindings.changePlan,
                changePlanCliProjector,
            ),
        changeApply =
            TypedOperationProjection(
                CanonicalOperationWireBindings.changeApply,
                changeApplyCliProjector,
            ),
        changeRecover =
            TypedOperationProjection(
                CanonicalOperationWireBindings.changeRecover,
                changeRecoverCliProjector,
            ),
    )
