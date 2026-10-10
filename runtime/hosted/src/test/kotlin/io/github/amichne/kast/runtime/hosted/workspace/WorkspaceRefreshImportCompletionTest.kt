package io.github.amichne.kast.runtime.hosted.workspace

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
        val completion = WorkspaceRefreshImportCompletion<String>(results::add, observations::add)
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
        val completion = WorkspaceRefreshImportCompletion<String>(results::add, observations::add)
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
    fun `disposal reports retirement without manufacturing native task settlement`() {
        val results = mutableListOf<WorkspaceRefreshEffectResult>()
        val observations = mutableListOf<WorkspaceRefreshImportObservation>()
        val completion = WorkspaceRefreshImportCompletion<String>(results::add, observations::add)
        completion.retired()
        assertEquals(listOf(WorkspaceRefreshEffectResult.RETIRED), results)
        completion.finished(WorkspaceRefreshEffectResult.DISPOSED)
        completion.started("late")
        assertEquals(listOf(WorkspaceRefreshEffectResult.RETIRED), results)
        completion.ended("late")
        completion.retired()
        assertEquals(listOf(WorkspaceRefreshEffectResult.RETIRED, WorkspaceRefreshEffectResult.DISPOSED), results)
        assertEncodedObservation(observations.first(), "RETIRED")
        assertEncodedObservation(observations.last(), "DISPOSED")
    }

    @Test
    fun `start-call failure cannot manufacture native termination`() {
        val results = mutableListOf<WorkspaceRefreshEffectResult>()
        val completion = WorkspaceRefreshImportCompletion<String>(results::add) {}
        completion.finished(WorkspaceRefreshEffectResult.FAILED)
        assertEquals(emptyList<WorkspaceRefreshEffectResult>(), results)
        completion.started("selected")
        assertEquals(emptyList<WorkspaceRefreshEffectResult>(), results)
        completion.ended("selected")
        assertEquals(listOf(WorkspaceRefreshEffectResult.FAILED), results)
    }

    @Test
    fun `successful import application waits for native settlement in either order`() {
        for (order in listOf(listOf("callback", "end"), listOf("end", "callback"))) {
            val results = mutableListOf<WorkspaceRefreshEffectResult>()
            val completion = WorkspaceRefreshImportCompletion<String>(results::add) {}
            completion.started("selected")
            for ((index, event) in order.withIndex()) {
                when (event) {
                    "callback" -> completion.finished(WorkspaceRefreshEffectResult.SUCCEEDED)
                    "end" -> completion.ended("selected")
                }
                assertEquals(if (index == 0) emptyList() else listOf(WorkspaceRefreshEffectResult.SUCCEEDED), results)
            }
        }
    }

    @Test
    fun `callback without matching task settlement cannot complete native work`() {
        val results = mutableListOf<WorkspaceRefreshEffectResult>()
        val completion = WorkspaceRefreshImportCompletion<String>(results::add) {}
        completion.finished(WorkspaceRefreshEffectResult.SUCCEEDED)
        completion.started("selected")
        completion.ended("other")
        assertEquals(emptyList<WorkspaceRefreshEffectResult>(), results)
        completion.ended("selected")
        assertEquals(listOf(WorkspaceRefreshEffectResult.SUCCEEDED), results)
    }

    @Test
    fun `conflicting imported callbacks cannot erase failure before settlement`() {
        for (results in
            listOf(
                listOf(WorkspaceRefreshEffectResult.FAILED, WorkspaceRefreshEffectResult.SUCCEEDED),
                listOf(WorkspaceRefreshEffectResult.SUCCEEDED, WorkspaceRefreshEffectResult.FAILED),
            )) {
            val completed = mutableListOf<WorkspaceRefreshEffectResult>()
            val completion = WorkspaceRefreshImportCompletion<String>(completed::add) {}
            completion.started("selected")
            results.forEach(completion::finished)
            completion.ended("selected")
            assertEquals(listOf(WorkspaceRefreshEffectResult.FAILED), completed)
        }
    }

    @Test
    fun `generated task event prefixes never publish success without application and matching end`() {
        val events = listOf("start-a", "start-b", "success", "failure", "cancel-a", "end-a", "end-b", "dispose")
        forEachBoundedPrefix(events, 4) { prefix ->
            val case = ImportPrefixCase()
            prefix.forEach { event ->
                case.accept(event)
                case.assertInvariant(prefix)
            }
        }
    }

    private fun assertEncodedObservation(observation: WorkspaceRefreshImportObservation, outcome: String) {
        val encoded = Json.parseToJsonElement(Json.encodeToString(observation)).jsonObject
        assertEquals(setOf("stage", "outcome"), encoded.keys)
        assertEquals("IMPORT_CALLBACK", encoded.getValue("stage").jsonPrimitive.content)
        assertEquals(outcome, encoded.getValue("outcome").jsonPrimitive.content)
    }

    private fun task(project: String) = project
}

/** Oracle records application and matching task end independently of production completion state. */
private class ImportPrefixCase {
    private val results = mutableListOf<WorkspaceRefreshEffectResult>()
    private val completion = WorkspaceRefreshImportCompletion<String>(results::add) {}
    private var selected: String? = null
    private var applied = false
    private var ended = false

    fun accept(event: String) {
        when (event) {
            "start-a",
            "start-b" -> start(event.removePrefix("start-"))
            "success" -> applyImport()
            "failure" -> completion.finished(WorkspaceRefreshEffectResult.FAILED)
            "cancel-a" -> completion.cancelled("a")
            "end-a",
            "end-b" -> end(event.removePrefix("end-"))
            "dispose" -> completion.retired()
        }
    }

    private fun start(id: String) {
        if (selected == null) selected = id
        completion.started(id)
    }

    private fun applyImport() {
        applied = true
        completion.finished(WorkspaceRefreshEffectResult.SUCCEEDED)
    }

    private fun end(id: String) {
        if (selected == id) ended = true
        completion.ended(id)
    }

    fun assertInvariant(prefix: List<String>) {
        assertEquals(true, results.filter { it != WorkspaceRefreshEffectResult.RETIRED }.size <= 1, prefix.toString())
        assertEquals(true, results.size <= 2, prefix.toString())
        if (WorkspaceRefreshEffectResult.SUCCEEDED in results) assertEquals(true, applied && ended, prefix.toString())
    }
}
