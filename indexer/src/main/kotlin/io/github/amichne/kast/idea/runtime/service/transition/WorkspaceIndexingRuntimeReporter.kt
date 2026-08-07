package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.idea.diagnostics.KastDiagnosticsService
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPreparation
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPublicationOutcome
import io.github.amichne.kast.idea.transition.WorkspaceTransitionSnapshot
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
import io.github.amichne.kast.indexstore.snapshot.SnapshotPublicationResult
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore

internal class WorkspaceIndexingRuntimeReporter(
    private val project: Project,
    private val workspaceIdentity: IdeaWorkspaceIdentity,
    private val diagnostics: KastDiagnosticsService,
    private val snapshotPreparation: RepositorySnapshotPreparation,
    private val indexStore: SqliteSourceIndexStore,
    private val isCancelled: () -> Boolean,
) {
    fun completed(summary: KastSourceIndexSummary) {
        if (isCancelled()) return
        runCatching {
            snapshotPreparation.publishCompletedIndex(indexStore)
        }.onSuccess(::recordSnapshotPublication)
            .onFailure { error ->
            LOG.warn("Kast repository snapshot publication failed", error)
        }
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
                    is PublishedWorkspaceGenerationState.Published -> publication.manifest.generation.value
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
