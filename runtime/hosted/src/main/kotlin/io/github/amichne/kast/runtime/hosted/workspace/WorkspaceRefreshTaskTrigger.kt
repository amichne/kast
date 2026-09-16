package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemExecuteTaskTask
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.project.Project
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshCommand
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshRule
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Exact successful IDE task observation only; resolve/import events cannot trigger a loop. */
internal class WorkspaceRefreshTaskTrigger(
    private val project: Project,
    private val root: CanonicalWorkspaceRoot,
    private val scope: CoroutineScope,
    private val request: (WorkspaceRefreshCommand.Request) -> Unit,
) : Disposable {
    private val policy = WorkspaceRefreshTriggerPolicy()
    private val selected = mutableMapOf<ExternalSystemTaskId, WorkspaceRefreshRule.TaskSuccess>()
    private val listener =
        object : ExternalSystemTaskNotificationListener {
            override fun onStart(id: ExternalSystemTaskId, workingDir: String) {
                if (
                    id.projectSystemId != IntellijWorkspaceRefreshPort.GRADLE ||
                        id.type != ExternalSystemTaskType.EXECUTE_TASK ||
                        id.findProject() !== project
                )
                    return
                val task =
                    ExternalSystemProcessingManager.getInstance().findTask(id) as? ExternalSystemExecuteTaskTask
                        ?: return
                if (task.externalProjectPath != root.value) return
                policy.select(task.tasksToExecute)?.let { rule ->
                    synchronized(selected) {
                        if (selected.size < MAXIMUM_TASKS) selected[id] = rule
                    }
                }
            }

            override fun onSuccess(id: ExternalSystemTaskId) {
                val rule = synchronized(selected) { selected.remove(id) } ?: return
                val effect = policy.success(rule) ?: return
                // No semantic readiness or lifecycle waiting in the Gradle callback.
                scope.launch { request(WorkspaceRefreshCommand.Request(UUID.randomUUID().toString(), effect)) }
            }

            override fun onFailure(id: ExternalSystemTaskId, e: Exception) {
                forget(id)
            }

            override fun onCancel(id: ExternalSystemTaskId) {
                forget(id)
            }

            override fun onEnd(id: ExternalSystemTaskId) {
                forget(id)
            }
        }

    init {
        ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(listener)
    }

    fun configure(rule: WorkspaceRefreshRule): WorkspaceRefreshResult = policy.configure(rule)

    private fun forget(id: ExternalSystemTaskId) {
        synchronized(selected) { selected.remove(id) }
    }

    override fun dispose() {
        policy.configure(WorkspaceRefreshRule.Off)
        synchronized(selected) { selected.clear() }
        ExternalSystemProgressNotificationManager.getInstance().removeNotificationListener(listener)
    }

    private companion object {
        const val MAXIMUM_TASKS = 64
    }
}

/** Pure exact matching; absence means the event does not select a configured effect. */
internal class WorkspaceRefreshTriggerPolicy {
    @Volatile private var rule: WorkspaceRefreshRule = WorkspaceRefreshRule.Off

    fun configure(candidate: WorkspaceRefreshRule): WorkspaceRefreshResult {
        if (
            candidate is WorkspaceRefreshRule.TaskSuccess &&
                (candidate.task.length > MAXIMUM_TASK_NAME || !EXACT_TASK.matches(candidate.task))
        )
            return WorkspaceRefreshResult.Rejected(WorkspaceRefreshFailure.INVALID_REQUEST)
        rule = candidate
        return WorkspaceRefreshResult.Configured(candidate)
    }

    fun select(tasks: List<String>): WorkspaceRefreshRule.TaskSuccess? =
        (rule as? WorkspaceRefreshRule.TaskSuccess)?.takeIf { tasks == listOf(it.task) }

    fun success(started: WorkspaceRefreshRule.TaskSuccess): WorkspaceRefreshEffect? =
        started.effect.takeIf { rule === started }

    private companion object {
        const val MAXIMUM_TASK_NAME = 512
        val EXACT_TASK = Regex(":[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*")
    }
}
