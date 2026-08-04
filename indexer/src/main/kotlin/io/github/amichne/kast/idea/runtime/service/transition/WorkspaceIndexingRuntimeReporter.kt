package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.idea.diagnostics.KastDiagnosticsService
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotCoordinator
import io.github.amichne.kast.idea.transition.WorkspaceLifecycle
import io.github.amichne.kast.idea.transition.WorkspaceTransitionSnapshot
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore

internal class WorkspaceIndexingRuntimeReporter(
    private val project: Project,
    private val workspaceIdentity: IdeaWorkspaceIdentity,
    private val diagnostics: KastDiagnosticsService,
    private val snapshotCoordinator: RepositorySnapshotCoordinator?,
    private val indexStore: SqliteSourceIndexStore,
    private val isCancelled: () -> Boolean,
) {
    fun completed(summary: KastSourceIndexSummary) {
        if (isCancelled()) return
        snapshotCoordinator?.let { coordinator ->
            runCatching {
                coordinator.publishCompletedIndex(indexStore)
            }.onFailure { error ->
                LOG.warn("Kast repository snapshot publication failed", error)
            }
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
        snapshot.publicationWarning
            ?.takeIf { snapshot.lifecycle == WorkspaceLifecycle.Ready }
            ?.let { warning ->
                LOG.warn(
                    "Kast workspace generation ${warning.manifest.generation.value} is current, but pointer " +
                        "directory durability is uncertain",
                    warning.cause,
                )
            }
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
                "publishedGeneration" to snapshot.published?.generation?.value,
                "blockerPhase" to snapshot.blocker?.phase?.name,
                "blockerDetail" to snapshot.blocker?.detail,
                "publicationDurability" to if (snapshot.publicationWarning == null) "DURABLE" else "UNCERTAIN",
                "publicationWarningClass" to snapshot.publicationWarning?.cause?.javaClass?.name,
                "publicationWarningDetail" to snapshot.publicationWarning?.cause?.message,
            ) + workspaceIdentity.traceDetails(),
        )
    }

    private companion object {
        val LOG: Logger = Logger.getInstance(KastIdeaProjectIndexing::class.java)
    }
}
