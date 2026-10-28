package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemResolveProjectTask
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.util.concurrent.CompletableFuture
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The import callback omits cancellation; the task listener supplies that terminal signal after native work ends. */
internal class WorkspaceRefreshImportCallback(
    private val project: Project,
    private val root: CanonicalWorkspaceRoot,
    complete: (WorkspaceRefreshEffectResult) -> Unit,
) : Disposable {
    // IntelliJ keeps its callback in build history. Its future adapter releases our dependent after completion.
    val nativeCompletion = CompletableFuture<Void>()
    private var abortedBeforeStart = false
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
    private val completion: WorkspaceRefreshImportCompletion<ExternalSystemTaskId> =
        WorkspaceRefreshImportCompletion(
            complete = { result ->
                if (result != WorkspaceRefreshEffectResult.RETIRED) {
                    nativeCompletion.cancel(false)
                    manager.removeNotificationListener(listener)
                    if (!Disposer.isDisposed(this)) Disposer.dispose(this)
                }
                complete(result)
            },
            observe = { observation ->
                Logger.getInstance(WorkspaceRefreshImportCallback::class.java)
                    .info("kast_workspace_import " + Json.encodeToString(observation))
            },
        )

    init {
        nativeCompletion.whenComplete { _, failure ->
            completion.finished(
                when {
                    project.isDisposed -> WorkspaceRefreshEffectResult.DISPOSED
                    failure != null -> WorkspaceRefreshEffectResult.FAILED
                    // This is the same model source that ExternalSystemUtil reads before its success callback.
                    ProjectDataManager.getInstance()
                        .getExternalProjectData(project, IntellijWorkspaceRefreshPort.GRADLE, root.value)
                        ?.externalProjectStructure == null -> WorkspaceRefreshEffectResult.FAILED
                    else -> WorkspaceRefreshEffectResult.SUCCEEDED
                }
            )
        }
        manager.addNotificationListener(listener)
        if (!Disposer.tryRegister(project, this)) dispose()
    }

    fun failedStartCall(result: WorkspaceRefreshEffectResult) = completion.finished(result)

    fun abortBeforeStart() {
        abortedBeforeStart = true
        nativeCompletion.cancel(false)
        manager.removeNotificationListener(listener)
        if (!Disposer.isDisposed(this)) Disposer.dispose(this)
    }

    override fun dispose() {
        if (!abortedBeforeStart) completion.retired()
    }
}
