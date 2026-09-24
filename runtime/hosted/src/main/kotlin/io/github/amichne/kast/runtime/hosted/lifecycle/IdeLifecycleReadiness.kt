package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.application.EDT
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.project.Project
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.runtime.hosted.saveProjectDocuments
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded existing-project readiness sequence, with at most one Kast-owned model reload. */
internal class IdeLifecycleReadiness(
    private val project: Project,
    private val target: IdeProjectTarget,
    private val root: CanonicalWorkspaceRoot,
    private val query: HostedQueryService,
    private val refreshExistingModel: Boolean = false,
    private val reloadModel: suspend () -> IdeLifecycleResult,
) {
    private var reloaded = false
    private var saved = false

    suspend fun await(): IdeLifecycleResult =
        withTimeoutOrNull(OPERATION_WAIT_MILLIS) {
            while (true) {
                if (project.isDisposed) return@withTimeoutOrNull blocked(IdeLifecycleFailure.DISPOSED)
                when (val step = step()) {
                    ReadinessStep.Wait -> delay(POLL_MILLIS)
                    is ReadinessStep.Finished -> return@withTimeoutOrNull step.result
                }
            }
            @Suppress("UNREACHABLE_CODE") blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
        } ?: blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)

    private suspend fun step(): ReadinessStep =
        when (query.readiness(root).automaticAction()) {
            AutomaticReadinessAction.Ready ->
                if (refreshExistingModel && !reloaded) reloadIfIdle()
                else ReadinessStep.Finished(IdeLifecycleResult.Opened(target))
            AutomaticReadinessAction.ReloadModel -> reloadIfIdle()
            AutomaticReadinessAction.Wait -> ReadinessStep.Wait
            AutomaticReadinessAction.UnsavedDocuments ->
                if (!saved && withContext(Dispatchers.EDT) { saveProjectDocuments(root) }) {
                    saved = true
                    ReadinessStep.Wait
                } else ReadinessStep.Finished(blocked(IdeLifecycleFailure.UNSAVED_DOCUMENTS))
        }

    private suspend fun reloadIfIdle(): ReadinessStep {
        if (
            ExternalSystemProcessingManager.getInstance()
                .hasTaskOfTypeInProgress(ExternalSystemTaskType.RESOLVE_PROJECT, project)
        )
            return ReadinessStep.Wait
        if (reloaded) return ReadinessStep.Finished(blocked(IdeLifecycleFailure.IMPORT_FAILED))
        reloaded = true
        return when (val result = reloadModel()) {
            is IdeLifecycleResult.Synced -> ReadinessStep.Wait
            else -> ReadinessStep.Finished(result)
        }
    }

    private fun blocked(failure: IdeLifecycleFailure) = IdeLifecycleResult.Blocked(failure)

    private sealed interface ReadinessStep {
        data object Wait : ReadinessStep

        data class Finished(val result: IdeLifecycleResult) : ReadinessStep
    }

    private companion object {
        const val OPERATION_WAIT_MILLIS = 120_000L
        const val POLL_MILLIS = 100L
    }
}
