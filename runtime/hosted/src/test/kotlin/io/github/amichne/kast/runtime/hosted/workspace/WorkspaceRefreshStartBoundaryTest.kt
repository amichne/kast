package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessFixture
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceRefreshStartBoundaryTest {
    private val facts =
        WorkspaceReadinessFixture(
            (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
        )

    private fun id(n: Int) = (WorkspaceRefreshRequestId.parse("fault-$n") as Refinement.Refined).value

    @Test
    fun `preflight failure proves no native start and a later attempt retries successfully`() {
        for (failure in listOf(WorkspaceRefreshEffectResult.FAILED, WorkspaceRefreshEffectResult.CANCELLED)) {
            var fail = true
            var starts = 0
            val port =
                object : WorkspaceRefreshPort {
                    override fun readiness() = facts.ready()

                    override fun startIncremental(complete: (WorkspaceRefreshEffectResult) -> Unit) =
                        start(WorkspaceRefreshEffect.FILE_REFRESH, complete)

                    override fun start(
                        effect: WorkspaceRefreshEffect,
                        complete: (WorkspaceRefreshEffectResult) -> Unit,
                    ) {
                        val boundary = WorkspaceRefreshStartBoundary()
                        boundary.run({ failure }, complete, { error("Preflight cannot be unsettled") }) {
                            if (fail) throw IllegalStateException("Injected document or lookup failure")
                            boundary.mayHaveStarted()
                            starts++
                            complete(WorkspaceRefreshEffectResult.SUCCEEDED)
                        }
                    }
                }
            val service = WorkspaceRefreshService(port)
            assertTrue(service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH) is WorkspaceRefreshStatus.Failed)
            assertFalse(service.hasWork())
            fail = false
            assertEquals(WorkspaceRefreshStatus.Complete, service.submit(id(2), WorkspaceRefreshEffect.FILE_REFRESH))
            assertEquals(1, starts)
        }
    }

    @Test
    fun `scheduler exception and timeout cannot prove possibly started work settled`() {
        var now = 0L
        var starts = 0
        var unsettled = 0
        val port =
            object : WorkspaceRefreshPort {
                override fun readiness() = facts.ready()

                override fun startIncremental(complete: (WorkspaceRefreshEffectResult) -> Unit) =
                    start(WorkspaceRefreshEffect.FILE_REFRESH, complete)

                override fun start(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit) {
                    val boundary = WorkspaceRefreshStartBoundary()
                    boundary.run({ WorkspaceRefreshEffectResult.FAILED }, complete, { unsettled++ }) {
                        boundary.mayHaveStarted()
                        starts++
                        throw IllegalStateException("Scheduling may already have launched work")
                    }
                }
            }
        val service = WorkspaceRefreshService(port, { now }, pendingTimeoutNanos = 10)
        service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH)
        now = 10
        assertTrue(service.status(id(1)) is WorkspaceRefreshStatus.Failed)
        service.submit(id(2), WorkspaceRefreshEffect.FILE_REFRESH)
        assertEquals(1, starts)
        assertEquals(1, unsettled)
        assertTrue(service.inspection() is WorkspaceRefreshInspection.Running)
    }
}
