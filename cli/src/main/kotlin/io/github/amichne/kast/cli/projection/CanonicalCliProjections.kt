package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliRequestPreparer
import io.github.amichne.kast.cli.TypedCliProjection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** The exact generated request preparations consumed by the canonical command graph. */
internal class CanonicalCliRequestPreparers(
    val indexSync: CliRequestPreparer<IndexSyncRequest>,
    val topologyBuild: CliRequestPreparer<TopologyBuildRequest>,
    val symbolDiscover: CliRequestPreparer<SymbolDiscoverRequest>,
    val symbolInspect: CliRequestPreparer<SymbolInspectRequest>,
    val sourceRead: CliRequestPreparer<SourceReadRequest>,
    val relationRead: CliRequestPreparer<RelationReadRequest>,
    val traversalRun: CliRequestPreparer<TraversalRunRequest>,
    val diagnosticCheck: CliRequestPreparer<DiagnosticCheckRequest>,
    val changePlan: CliRequestPreparer<ChangePlanRequest>,
    val changeApply: CliRequestPreparer<ChangeApplyRequest>,
    val changeRecover: CliRequestPreparer<ChangeRecoverRequest>,
)

/** Captures every generated wire binding behind its concrete request type. */
internal fun canonicalCliRequestPreparers(): CanonicalCliRequestPreparers =
    CanonicalCliRequestPreparers(
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
        symbolInspect = TypedCliProjection(
            CanonicalOperationWireBindings.symbolInspect,
            symbolInspectCliProjector,
        ),
        sourceRead = TypedCliProjection(
            CanonicalOperationWireBindings.sourceRead,
            sourceReadCliProjector,
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
        changeRecover = TypedCliProjection(
            CanonicalOperationWireBindings.changeRecover,
            changeRecoverCliProjector,
        ),
    )
