package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessFixture
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AutomaticReadinessTest {
    private val facts =
        WorkspaceReadinessFixture(
            (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
        )

    @Test
    fun `current native Ready completes preparation without any metadata-triggered reload`() {
        val preparation = WorkspaceReadinessPreparation()
        repeat(4) { assertEquals(WorkspacePreparationAction.Ready, preparation.observe(facts.ready())) }
    }

    @Test
    fun `native settlement and indexing wait without starting competing reloads`() {
        val preparation = WorkspaceReadinessPreparation()
        listOf(
                WorkspaceReadinessReason.NATIVE_WORK to WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT,
                WorkspaceReadinessReason.INDEXING to WorkspaceReadinessNextAction.OBSERVE_AGAIN,
            )
            .forEach { (reason, action) ->
                assertEquals(
                    WorkspacePreparationAction.Wait,
                    preparation.observe(WorkspaceCapabilityReadiness.Pending(facts.identity, reason, action)),
                )
            }
        assertEquals(WorkspacePreparationAction.Ready, preparation.observe(facts.ready()))
    }

    @Test
    fun `native missing model requests one bounded owner reload and later preparation may retry`() {
        val missing =
            WorkspaceCapabilityReadiness.Unavailable(
                facts.identity,
                WorkspaceReadinessReason.MODEL_UNAVAILABLE,
                WorkspaceReadinessNextAction.REFRESH_MODEL,
            )
        val preparation = WorkspaceReadinessPreparation()
        assertEquals(WorkspacePreparationAction.ReloadModel, preparation.observe(missing))
        assertEquals(
            WorkspacePreparationAction.Blocked(IdeLifecycleFailure.IMPORT_FAILED),
            preparation.observe(missing),
        )
        assertEquals(WorkspacePreparationAction.Ready, preparation.observe(facts.ready()))
        assertEquals(WorkspacePreparationAction.ReloadModel, WorkspaceReadinessPreparation().observe(missing))
    }

    @Test
    fun `all bounded observation interleavings preserve Ready and bounded reload decisions`() {
        val missing =
            WorkspaceCapabilityReadiness.Unavailable(
                facts.identity,
                WorkspaceReadinessReason.MODEL_INCOMPLETE,
                WorkspaceReadinessNextAction.REFRESH_MODEL,
            )
        val events =
            listOf(
                facts.ready(),
                missing,
                WorkspaceCapabilityReadiness.Pending(
                    facts.identity,
                    WorkspaceReadinessReason.NATIVE_WORK,
                    WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT,
                ),
            )
        fun check(prefix: List<WorkspaceCapabilityReadiness>) {
            val preparation = WorkspaceReadinessPreparation()
            var reloads = 0
            prefix.forEach { event ->
                val result = preparation.observe(event)
                if (event is WorkspaceCapabilityReadiness.Ready) assertEquals(WorkspacePreparationAction.Ready, result)
                if (result == WorkspacePreparationAction.ReloadModel) reloads++
                org.junit.jupiter.api.Assertions.assertTrue(reloads <= 1)
            }
            if (prefix.size < 4) events.forEach { check(prefix + it) }
        }
        check(emptyList())
    }
}
