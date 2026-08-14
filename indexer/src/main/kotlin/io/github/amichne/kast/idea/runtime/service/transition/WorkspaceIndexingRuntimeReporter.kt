package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.idea.diagnostics.KastDiagnosticsService
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPublication
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPublicationOutcome
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionSnapshot
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.indexstore.snapshot.SnapshotPublicationResult
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore

/** Carries the exact state identity, indexing input, and snapshot capability captured together. */
internal data class WorkspaceReconciliationCandidate(
    val identity: WorkspaceStateIdentity,
    val indexingCandidate: WorkspaceIndexingCandidate?,
    val snapshotPublication: RepositorySnapshotPublication,
)

internal data class CompletedWorkspaceReconciliation(
    val summary: KastSourceIndexSummary,
    val snapshotPublication: RepositorySnapshotPublication,
)

internal sealed interface PendingCompletedWorkspaceReconciliation {
    data object Absent : PendingCompletedWorkspaceReconciliation

    data class Available(
        val reconciliation: CompletedWorkspaceReconciliation,
    ) : PendingCompletedWorkspaceReconciliation

    /**
     * Proof transition:
     * `PendingCompletedWorkspaceReconciliation -> CompletedWorkspaceReconciliation`.
     *
     * Extracts only the completion capability produced by an admitted READY
     * publication. [Absent] is an internal coordinator invariant violation,
     * not an expected runtime outcome or nullable control state.
     */
    fun requireCompletion(): CompletedWorkspaceReconciliation = when (this) {
        Absent -> error("Published workspace transition has no completed reconciliation")
        is Available -> reconciliation
    }
}

internal class WorkspaceIndexingRuntimeReporter(
    private val project: Project,
    private val workspaceIdentity: IdeaWorkspaceIdentity,
    private val diagnostics: KastDiagnosticsService,
    private val indexStore: SqliteSourceIndexStore,
    private val isCancelled: () -> Boolean,
) {
    fun completed(reconciliation: CompletedWorkspaceReconciliation) {
        if (isCancelled()) return
        runCatching {
            reconciliation.snapshotPublication.publish(indexStore)
        }.onSuccess(::recordSnapshotPublication)
            .onFailure { error ->
                LOG.warn("Kast repository snapshot publication failed", error)
            }
        val summary = reconciliation.summary
        KastStructuredTrace.event(
            eventName = "idea.index.completed",
            project = project,
            workspaceRoot = workspaceIdentity.workspaceRootPath,
            fields = KastStructuredTraceFields(agentRole = "idea-indexer"),
            outcome = "completed",
            detail = mapOf(
                "fileCount" to summary.fileCount,
                "identifierCount" to summary.identifierCount,
                "moduleCount" to summary.moduleCount,
                "importCount" to summary.importCount,
            ) + workspaceIdentity.traceDetails(),
        )
        diagnostics.recordIndexCompleted(summary)
        LOG.info("Kast IDEA project index completed")
    }

    fun failed(error: Throwable) {
        if (isCancelled()) return
        KastStructuredTrace.event(
            eventName = "idea.index.failed",
            project = project,
            workspaceRoot = workspaceIdentity.workspaceRootPath,
            fields = KastStructuredTraceFields(agentRole = "idea-indexer"),
            outcome = "failed",
            detail = mapOf(
                "errorClass" to error::class.qualifiedName,
                "message" to error.message,
            ) + workspaceIdentity.traceDetails(),
        )
        diagnostics.recordIndexFailed(error)
        LOG.warn("Kast IDEA project index failed", error)
    }

    fun transitioned(snapshot: WorkspaceTransitionSnapshot) {
        KastStructuredTrace.event(
            eventName = "idea.index.workspace_transition",
            project = project,
            workspaceRoot = workspaceIdentity.workspaceRootPath,
            fields = KastStructuredTraceFields(agentRole = "idea-indexer"),
            outcome = snapshot.lifecycle.name.uppercase(),
            detail = mapOf(
                "lifecycle" to snapshot.lifecycle.name.uppercase(),
                "pendingSignals" to snapshot.pendingSignals.map { it.name }.sorted().joinToString(","),
                "observedEventCount" to snapshot.observedEventCount,
                "publishedGeneration" to when (val publication = snapshot.published) {
                    PublishedWorkspaceGenerationState.Unpublished -> null
                    is PublishedWorkspaceGenerationState.Published -> publication.publication.generation.value
                },
                "blockerPhase" to snapshot.blocker?.phase?.name,
                "blockerDetail" to snapshot.blocker?.detail,
            ) + workspaceIdentity.traceDetails(),
        )
    }

    private fun recordSnapshotPublication(outcome: RepositorySnapshotPublicationOutcome) {
        when (outcome) {
            RepositorySnapshotPublicationOutcome.UnmanagedWorkspace,
            RepositorySnapshotPublicationOutcome.SuppressedForWorktreeOverlay,
                -> Unit
            is RepositorySnapshotPublicationOutcome.Skipped ->
                LOG.debug("Kast repository snapshot publication skipped: ${outcome.reason}")
            is RepositorySnapshotPublicationOutcome.Completed -> when (val result = outcome.result) {
                is SnapshotPublicationResult.Published ->
                    LOG.info("Published Kast repository snapshot ${result.manifest.key.directoryName.value}")
                is SnapshotPublicationResult.Reused ->
                    LOG.debug("Reused Kast repository snapshot ${result.manifest.key.directoryName.value}")
                is SnapshotPublicationResult.Rejected ->
                    LOG.warn("Kast repository snapshot publication rejected: ${result.failure}")
            }
        }
    }

    private companion object {
        val LOG: Logger = Logger.getInstance(KastIdeaProjectIndexing::class.java)
    }
}
