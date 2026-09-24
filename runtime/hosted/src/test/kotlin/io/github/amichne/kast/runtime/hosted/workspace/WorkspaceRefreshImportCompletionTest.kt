package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceRefreshImportCompletionTest {
    @Test
    fun `cancellation waits for matching task end and terminates exactly once`() {
        val results = mutableListOf<WorkspaceRefreshEffectResult>()
        val observations = mutableListOf<WorkspaceRefreshImportObservation>()
        val completion = WorkspaceRefreshImportCompletion(results::add, observations::add)
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
        assertEquals(listOf(WorkspaceRefreshEffectResult.CANCELLED), observations.map { it.outcome })
    }

    @Test
    fun `task end alone never manufactures imported model success`() {
        val results = mutableListOf<WorkspaceRefreshEffectResult>()
        val observations = mutableListOf<WorkspaceRefreshImportObservation>()
        val completion = WorkspaceRefreshImportCompletion(results::add, observations::add)
        val selected = task("selected")
        completion.started(selected)
        completion.ended(selected)
        assertEquals(emptyList<WorkspaceRefreshEffectResult>(), results)
        completion.finished(WorkspaceRefreshEffectResult.SUCCEEDED)
        completion.finished(WorkspaceRefreshEffectResult.FAILED)
        assertEquals(listOf(WorkspaceRefreshEffectResult.SUCCEEDED), results)
        assertEncodedObservation(observations.single(), "SUCCEEDED")
    }

    @Test
    fun `start failure and project disposal release exactly once even without task start`() {
        for (result in listOf(WorkspaceRefreshEffectResult.FAILED, WorkspaceRefreshEffectResult.DISPOSED)) {
            val results = mutableListOf<WorkspaceRefreshEffectResult>()
            val observations = mutableListOf<WorkspaceRefreshImportObservation>()
            val completion = WorkspaceRefreshImportCompletion(results::add, observations::add)
            completion.finished(result)
            completion.started(task("late"))
            completion.finished(WorkspaceRefreshEffectResult.SUCCEEDED)
            assertEquals(listOf(result), results)
            assertEncodedObservation(observations.single(), result.name)
        }
    }

    private fun assertEncodedObservation(observation: WorkspaceRefreshImportObservation, outcome: String) {
        val encoded = Json.parseToJsonElement(Json.encodeToString(observation)).jsonObject
        assertEquals(setOf("stage", "outcome"), encoded.keys)
        assertEquals("IMPORT_CALLBACK", encoded.getValue("stage").jsonPrimitive.content)
        assertEquals(outcome, encoded.getValue("outcome").jsonPrimitive.content)
    }

    private fun task(project: String) =
        ExternalSystemTaskId.create(
            ProjectSystemId("GRADLE"),
            ExternalSystemTaskType.RESOLVE_PROJECT,
            project,
        )
}
