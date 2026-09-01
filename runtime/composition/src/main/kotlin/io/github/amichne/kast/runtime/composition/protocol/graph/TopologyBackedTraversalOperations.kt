package io.github.amichne.kast.runtime.composition.protocol.graph

import io.github.amichne.kast.evidence.sqlite.SqliteTopologyRelationCompiler
import io.github.amichne.kast.evidence.sqlite.SqliteTopologyRelationCompilerOpening
import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastSpanCompletion
import io.github.amichne.kast.kernel.KastSpanCount
import io.github.amichne.kast.kernel.KastSpanFailure
import io.github.amichne.kast.kernel.KastSpanMeasurement
import io.github.amichne.kast.kernel.KastSpanName
import io.github.amichne.kast.kernel.KastSpanObservation
import io.github.amichne.kast.kernel.KastTraceSpan
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.topology.contract.TopologySnapshotContentReader
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotReader
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.service.traversalOperations
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Public traversal router whose only repository graph backend is an eligible SQLite snapshot. */
internal class TopologyBackedTraversalOperations(
    private val workspaces: WorkspaceInspectionOperations,
    private val snapshotReader: TopologySnapshotReader,
    private val contentReader: TopologySnapshotContentReader,
    private val observability: KastObservability = KastObservability.Disabled,
) : TraversalOperations {
    override suspend fun run(plan: TraversalPlan): TraversalResult = observability.inSpan(
        KastSpanName.TRAVERSAL_RUN,
    ) { span ->
        runObserved(plan, span).also { result -> span.observe(result.traceObservation()) }
    }

    private suspend fun runObserved(
        plan: TraversalPlan,
        trace: KastTraceSpan,
    ): TraversalResult {
        val workspace = when (val state = trace.child(KastSpanName.TRAVERSAL_WORKSPACE) { span ->
            workspaces.inspect().also { observed ->
                span.observe(
                    if (observed is WorkspaceRuntimeState.Ready) {
                        completeObservation()
                    } else {
                        rejectedObservation(KastSpanFailure.TRAVERSAL_WORKSPACE_NOT_READY)
                    },
                )
            }
        }) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
                -> return rejected(TraversalRejection.OneHopRejected(
                    RelationReadRejection.WORKSPACE_NOT_READY,
                ))
        }
        if (plan.start.lease != workspace.readLease) {
            return rejected(TraversalRejection.OneHopRejected(
                RelationReadRejection.STALE_GENERATION,
            ))
        }
        val snapshot = when (val eligible = trace.child(
            KastSpanName.TRAVERSAL_SNAPSHOT_ELIGIBILITY,
        ) { span ->
            snapshotReader.eligible(TopologyWorkspaceIdentity.from(workspace)).also { result ->
                span.observe(
                    when (result) {
                        is TopologySnapshotEligibility.Eligible -> completeObservation()
                        is TopologySnapshotEligibility.Stale -> rejectedObservation(
                            KastSpanFailure.TRAVERSAL_EVIDENCE_STALE,
                        )
                        TopologySnapshotEligibility.Unavailable,
                        is TopologySnapshotEligibility.Rejected,
                            -> rejectedObservation(
                                KastSpanFailure.TRAVERSAL_EVIDENCE_UNAVAILABLE,
                            )
                    },
                )
            }
        }) {
            is TopologySnapshotEligibility.Eligible -> eligible.snapshot
            is TopologySnapshotEligibility.Stale ->
                return rejected(TraversalRejection.RequiredEvidenceStale)
            TopologySnapshotEligibility.Unavailable,
            is TopologySnapshotEligibility.Rejected,
                -> return rejected(TraversalRejection.RequiredEvidenceUnavailable)
        }
        val compiler = when (val opened = trace.child(
            KastSpanName.TRAVERSAL_SNAPSHOT_OPEN,
        ) { span ->
            SqliteTopologyRelationCompiler.open(snapshot, contentReader).also { result ->
                span.observe(
                    when (result) {
                        is SqliteTopologyRelationCompilerOpening.Opened -> completeObservation()
                        is SqliteTopologyRelationCompilerOpening.Rejected -> rejectedObservation(
                            KastSpanFailure.TRAVERSAL_EVIDENCE_UNAVAILABLE,
                        )
                    },
                )
            }
        }) {
            is SqliteTopologyRelationCompilerOpening.Opened -> opened.compiler
            is SqliteTopologyRelationCompilerOpening.Rejected ->
                return rejected(TraversalRejection.RequiredEvidenceUnavailable)
        }
        val relations = RelationService(workspaces, compiler)
        return trace.child(KastSpanName.TRAVERSAL_EXPANSION) { span ->
            when (val result = traversalOperations(relations).run(plan)) {
                is TraversalResult.Rejected -> when (val reason = result.reason) {
                    is TraversalRejection.OneHopRejected ->
                        if (reason.reason == RelationReadRejection.WORKSPACE_INDEX_UNAVAILABLE) {
                            rejected(TraversalRejection.RequiredEvidenceUnavailable)
                        } else {
                            result
                        }
                    TraversalRejection.ReaderContractViolation,
                    TraversalRejection.RequiredEvidenceStale,
                    TraversalRejection.RequiredEvidenceUnavailable,
                    TraversalRejection.TraversalContractViolation,
                        -> result
                }
                is TraversalResult.Complete,
                is TraversalResult.Qualified,
                    -> result
            }.also { result -> span.observe(result.traceObservation()) }
        }
    }
}

private fun rejected(reason: TraversalRejection): TraversalResult =
    TraversalResult.Rejected(reason)

private fun TraversalResult.traceObservation(): KastSpanObservation = when (this) {
    is TraversalResult.Complete -> completeObservation(
        KastSpanMeasurement.RecordCount(exactCount(page.records.size)),
    )
    is TraversalResult.Qualified -> KastSpanObservation(
        KastSpanCompletion.Qualified,
        setOf(KastSpanMeasurement.RecordCount(exactCount(page.records.size))),
    )
    is TraversalResult.Rejected -> rejectedObservation(
        when (val rejection = reason) {
            is TraversalRejection.OneHopRejected -> when (rejection.reason) {
                RelationReadRejection.WORKSPACE_NOT_READY ->
                    KastSpanFailure.TRAVERSAL_WORKSPACE_NOT_READY
                RelationReadRejection.STALE_GENERATION ->
                    KastSpanFailure.TRAVERSAL_STALE_GENERATION
                else -> KastSpanFailure.TRAVERSAL_ONE_HOP
            }
            TraversalRejection.RequiredEvidenceStale ->
                KastSpanFailure.TRAVERSAL_EVIDENCE_STALE
            TraversalRejection.RequiredEvidenceUnavailable ->
                KastSpanFailure.TRAVERSAL_EVIDENCE_UNAVAILABLE
            TraversalRejection.ReaderContractViolation ->
                KastSpanFailure.TRAVERSAL_READER_CONTRACT
            TraversalRejection.TraversalContractViolation ->
                KastSpanFailure.TRAVERSAL_CONTRACT
        },
    )
}

private fun completeObservation(
    vararg measurements: KastSpanMeasurement,
): KastSpanObservation = KastSpanObservation(
    KastSpanCompletion.Complete,
    measurements.toSet(),
)

private fun rejectedObservation(failure: KastSpanFailure): KastSpanObservation =
    KastSpanObservation(KastSpanCompletion.Rejected(failure))

private fun exactCount(value: Int): KastSpanCount = when (
    val parsed = KastSpanCount.parse(value.toLong())
) {
    is Refinement.Refined -> parsed.value
    is Refinement.Rejected -> error("Collection size cannot be negative")
}
