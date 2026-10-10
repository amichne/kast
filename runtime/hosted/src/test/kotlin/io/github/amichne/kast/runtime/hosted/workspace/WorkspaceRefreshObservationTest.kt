package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class WorkspaceRefreshObservationTest {
    @Test
    fun `lifecycle observes on background context and leaves native effects at the port`() = runTest {
        val caller = StandardTestDispatcher(testScheduler, "native caller")
        val background = StandardTestDispatcher(testScheduler, "passive observer")
        val port = WorkspaceRefreshTestPort().apply { ready = true }
        val service = WorkspaceRefreshService(port, { 0L })
        withContext(caller) {
            assertSame(caller, currentCoroutineContext()[ContinuationInterceptor])
            observeWorkspaceRefresh(background) {
                assertSame(background, currentCoroutineContext()[ContinuationInterceptor])
                val id = (WorkspaceRefreshRequestId.parse("thread-boundary") as Refinement.Refined).value
                assertEquals(
                    WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.EFFECT),
                    service.submit(id, WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD),
                )
            }
            assertSame(caller, currentCoroutineContext()[ContinuationInterceptor])
        }
        assertEquals(listOf(WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD), port.effects)
    }
}
