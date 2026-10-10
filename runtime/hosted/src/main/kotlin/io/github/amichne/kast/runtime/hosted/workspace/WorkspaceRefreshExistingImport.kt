package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot

/** Observe both application and settlement of the existing import; never start a competing resolver. */
internal fun observeExistingWorkspaceImport(
    project: Project,
    root: CanonicalWorkspaceRoot,
    complete: (WorkspaceRefreshEffectResult) -> Unit,
): Boolean {
    val task = ExternalSystemProcessingManager.getInstance().findTask(
        ExternalSystemTaskType.RESOLVE_PROJECT,
        IntellijWorkspaceRefreshPort.GRADLE,
        root.value,
    ) ?: return false
    if (task.id.findProject() !== project) return false
    ExistingWorkspaceImportObservation(project, root, task.id, { task.state.isStopped }, complete)
    return true
}

private class ExistingWorkspaceImportObservation(
    project: Project,
    root: CanonicalWorkspaceRoot,
    private val taskId: ExternalSystemTaskId,
    alreadyEnded: () -> Boolean,
    complete: (WorkspaceRefreshEffectResult) -> Unit,
) : Disposable {
    private val manager = ExternalSystemProgressNotificationManager.getInstance()
    private val listener = object : ExternalSystemTaskNotificationListener {
        override fun onFailure(id: ExternalSystemTaskId, exception: Exception) {
            if (id == taskId) completion.finished(WorkspaceRefreshEffectResult.FAILED)
        }
        override fun onCancel(id: ExternalSystemTaskId) = completion.cancelled(id)
        override fun onEnd(id: ExternalSystemTaskId) = completion.ended(id)
    }

    private val completion = WorkspaceRefreshImportCompletion<ExternalSystemTaskId>(
        complete = { outcome ->
            if (outcome != WorkspaceRefreshEffectResult.RETIRED) {
                manager.removeNotificationListener(listener)
                if (!Disposer.isDisposed(this)) Disposer.dispose(this)
            }
            complete(outcome)
        },
        observe = { observation ->
            Logger.getInstance(ExistingWorkspaceImportObservation::class.java)
                .info("kast_workspace_import " + Json.encodeToString(observation))
        },
    )

    init {
        completion.started(taskId)
        manager.addNotificationListener(listener)
        project.messageBus.connect(this).subscribe(
            ProjectDataImportListener.TOPIC,
            object : ProjectDataImportListener {
                override fun onImportFinished(projectPath: String) {
                    if (projectPath == root.value) completion.finished(WorkspaceRefreshEffectResult.SUCCEEDED)
                }
                override fun onImportFailed(projectPath: String) {
                    if (projectPath == root.value) completion.finished(WorkspaceRefreshEffectResult.FAILED)
                }
            },
        )
        if (!Disposer.tryRegister(project, this)) completion.retired()
        else if (alreadyEnded()) completion.ended(taskId)
        // A lost application callback remains unresolved. Task success or elapsed time cannot replace it.
    }

    override fun dispose() = completion.retired()
}
