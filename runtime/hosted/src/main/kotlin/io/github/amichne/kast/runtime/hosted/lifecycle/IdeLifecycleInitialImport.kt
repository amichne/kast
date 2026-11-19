package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshCommand
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult
import io.github.amichne.kast.runtime.hosted.HostedEndpointService
import io.github.amichne.kast.runtime.hosted.workspace.observeWorkspaceRefresh
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.settings.GradleSettings

private enum class InitialImportSelection {
    INITIAL_LINK,
    EXISTING_LINK,
    MISMATCH,
}

/** Native link selection runs on EDT; model observation and owner submission run on the background boundary. */
private suspend fun requestInitialWorkspaceImport(
    project: Project,
    endpoint: HostedEndpointService,
    root: CanonicalWorkspaceRoot,
    operation: NativeWorkspacePreparation,
): WorkspaceRefreshResult {
    val selection =
        withContext(Dispatchers.EDT) {
            val settings = GradleSettings.getInstance(project).linkedProjectsSettings
            when {
                settings.isEmpty() -> InitialImportSelection.INITIAL_LINK
                settings.any { it.externalProjectPath == root.value } -> InitialImportSelection.EXISTING_LINK
                else -> InitialImportSelection.MISMATCH
            }
        }
    return observeWorkspaceRefresh {
        when (selection) {
            InitialImportSelection.INITIAL_LINK ->
                endpoint.lifecycleInitialImport(operation.requestId, operation.deadline)
            InitialImportSelection.EXISTING_LINK ->
                when (val remaining = operation.remaining()) {
                    is Refinement.Rejected ->
                        WorkspaceRefreshResult.Failed(operation.requestId, WorkspaceRefreshFailure.DEADLINE_EXCEEDED)
                    is Refinement.Refined ->
                        endpoint.lifecycleRefresh(
                            WorkspaceRefreshCommand.Request(
                                operation.requestId,
                                WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD,
                                remaining.value.protocolMillis(),
                            )
                        )
                }
            InitialImportSelection.MISMATCH -> WorkspaceRefreshResult.Rejected(WorkspaceRefreshFailure.UNLINKED_BUILD)
        }
    }
}

internal suspend fun awaitInitialWorkspaceImport(
    project: Project,
    endpoint: HostedEndpointService,
    root: CanonicalWorkspaceRoot,
    operation: NativeWorkspacePreparation,
    scopes: List<InitialImportScope>,
    await: suspend (WorkspaceRefreshResult, () -> Unit) -> IdeLifecycleResult,
): IdeLifecycleResult {
    // A delayed IntelliJ restore can replace a Gradle model that was imported before the restore finished.
    if (
        !awaitLifecycleCondition(operation.deadline, operation.nanoTime) {
            project.isDisposed || scopes.all(InitialImportScope::canImportModel)
        }
    )
        return IdeLifecycleResult.Blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
    if (project.isDisposed) return IdeLifecycleResult.Blocked(IdeLifecycleFailure.DISPOSED)
    val initial = requestInitialWorkspaceImport(project, endpoint, root, operation)
    val resume = { scopes.forEach(InitialImportScope::resumeScanning) }
    return try {
        await(initial, resume)
    } finally {
        resume()
    }
}

internal suspend fun openedAfterImport(
    imported: IdeLifecycleResult,
    target: IdeProjectTarget,
    awaitReady: suspend () -> IdeLifecycleResult,
): IdeLifecycleResult =
    when (imported) {
        is IdeLifecycleResult.Synced -> IdeLifecycleResult.Opened(target)
        is IdeLifecycleResult.Blocked ->
            if (imported.reason == IdeLifecycleFailure.MODEL_RELOAD_REQUIRED) awaitReady() else imported
        else -> imported
    }
