package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemResolveProjectTask
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot

/** The import callback omits cancellation; the task listener supplies that terminal signal after native work ends. */
internal class WorkspaceRefreshImportCallback(
    private val project: Project,
    private val root: CanonicalWorkspaceRoot,
    complete: (WorkspaceRefreshEffectResult) -> Unit,
) : ExternalProjectRefreshCallback, Disposable {
    private val manager = ExternalSystemProgressNotificationManager.getInstance()
    private val listener: ExternalSystemTaskNotificationListener =
        object : ExternalSystemTaskNotificationListener {
            override fun onStart(id: ExternalSystemTaskId, workingDir: String) {
                if (
                    id.projectSystemId != IntellijWorkspaceRefreshPort.GRADLE ||
                        id.type != ExternalSystemTaskType.RESOLVE_PROJECT ||
                        id.findProject() !== project
                )
                    return
                val task =
                    ExternalSystemProcessingManager.getInstance().findTask(id) as? ExternalSystemResolveProjectTask
                        ?: return
                if (task.externalProjectPath == root.value) completion.started(id)
            }

            override fun onCancel(id: ExternalSystemTaskId) = completion.cancelled(id)

            override fun onEnd(id: ExternalSystemTaskId) = completion.ended(id)
        }
    private val completion: WorkspaceRefreshImportCompletion = WorkspaceRefreshImportCompletion { result ->
        manager.removeNotificationListener(listener)
        if (!Disposer.isDisposed(this)) Disposer.dispose(this)
        complete(result)
    }

    init {
        manager.addNotificationListener(listener)
        if (!Disposer.tryRegister(project, this)) dispose()
    }

    override fun onSuccess(id: ExternalSystemTaskId, externalProject: DataNode<ProjectData>?) {
        completion.finished(
            when {
                project.isDisposed -> WorkspaceRefreshEffectResult.DISPOSED
                externalProject == null -> WorkspaceRefreshEffectResult.FAILED
                else -> WorkspaceRefreshEffectResult.SUCCEEDED
            }
        )
    }

    override fun onFailure(id: ExternalSystemTaskId, errorMessage: String, errorDetails: String?) {
        completion.finished(
            if (project.isDisposed) WorkspaceRefreshEffectResult.DISPOSED else WorkspaceRefreshEffectResult.FAILED
        )
    }

    fun failedToStart(result: WorkspaceRefreshEffectResult) = completion.finished(result)

    override fun dispose() = completion.finished(WorkspaceRefreshEffectResult.DISPOSED)
}

/** Exactly one terminal signal; resolver success alone does not prove that imported data was applied. */
internal class WorkspaceRefreshImportCompletion(private val complete: (WorkspaceRefreshEffectResult) -> Unit) {
    private sealed interface State {
        data object Waiting : State

        data class Running(val id: ExternalSystemTaskId) : State

        data class Cancelling(val id: ExternalSystemTaskId) : State

        data object Finished : State
    }

    private var state: State = State.Waiting

    @Synchronized
    fun started(id: ExternalSystemTaskId) {
        if (state == State.Waiting) state = State.Running(id)
    }

    @Synchronized
    fun cancelled(id: ExternalSystemTaskId) {
        if (state == State.Running(id)) state = State.Cancelling(id)
    }

    @Synchronized
    fun ended(id: ExternalSystemTaskId) {
        if (state == State.Cancelling(id)) finished(WorkspaceRefreshEffectResult.CANCELLED)
    }

    @Synchronized
    fun finished(result: WorkspaceRefreshEffectResult) {
        if (state == State.Finished) return
        state = State.Finished
        complete(result)
    }
}
