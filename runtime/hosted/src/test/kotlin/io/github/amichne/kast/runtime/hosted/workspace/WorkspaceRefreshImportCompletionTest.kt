package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceRefreshImportCompletionTest {
    @Test
    fun `cancellation waits for matching task end and terminates exactly once`() {
        val results = mutableListOf<WorkspaceRefreshEffectResult>()
        val completion = WorkspaceRefreshImportCompletion(results::add)
        val selected = task("selected")
        val unrelated = task("other")
        completion.started(selected)
        completion.cancelled(unrelated)
        completion.ended(unrelated)
        assertEquals(emptyList<WorkspaceRefreshEffectResult>(), results)
        completion.cancelled(selected)
        assertEquals(emptyList<WorkspaceRefreshEffectResult>(), results)
        completion.ended(unrelated)
        assertEquals(emptyList<WorkspaceRefreshEffectResult>(), results)
        completion.ended(selected)
        completion.finished(WorkspaceRefreshEffectResult.SUCCEEDED)
        completion.finished(WorkspaceRefreshEffectResult.DISPOSED)
        assertEquals(listOf(WorkspaceRefreshEffectResult.CANCELLED), results)
    }

    @Test
    fun `task end alone never manufactures imported model success`() {
        val results = mutableListOf<WorkspaceRefreshEffectResult>()
        val completion = WorkspaceRefreshImportCompletion(results::add)
        val selected = task("selected")
        completion.started(selected)
        completion.ended(selected)
        assertEquals(emptyList<WorkspaceRefreshEffectResult>(), results)
        completion.finished(WorkspaceRefreshEffectResult.SUCCEEDED)
        completion.finished(WorkspaceRefreshEffectResult.FAILED)
        assertEquals(listOf(WorkspaceRefreshEffectResult.SUCCEEDED), results)
    }

    @Test
    fun `start failure and project disposal release exactly once even without task start`() {
        for (result in listOf(WorkspaceRefreshEffectResult.FAILED, WorkspaceRefreshEffectResult.DISPOSED)) {
            val results = mutableListOf<WorkspaceRefreshEffectResult>()
            val completion = WorkspaceRefreshImportCompletion(results::add)
            completion.finished(result)
            completion.started(task("late"))
            completion.finished(WorkspaceRefreshEffectResult.SUCCEEDED)
            assertEquals(listOf(result), results)
        }
    }

    private fun task(project: String) =
        ExternalSystemTaskId.create(
            ProjectSystemId("GRADLE"),
            ExternalSystemTaskType.RESOLVE_PROJECT,
            project,
        )
}
