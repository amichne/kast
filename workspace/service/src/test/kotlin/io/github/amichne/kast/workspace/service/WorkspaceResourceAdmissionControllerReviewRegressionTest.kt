package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceAdmissionWaitMillis
import io.github.amichne.kast.workspace.contract.WorkspaceConcurrencyLimit
import io.github.amichne.kast.workspace.contract.WorkspaceCriticalHeapPercent
import io.github.amichne.kast.workspace.contract.WorkspaceEdtLiveness
import io.github.amichne.kast.workspace.contract.WorkspaceExpensiveWork
import io.github.amichne.kast.workspace.contract.WorkspaceHeapUtilizationPercent
import io.github.amichne.kast.workspace.contract.WorkspaceQueueLimit
import io.github.amichne.kast.workspace.contract.WorkspaceResourceActivity
import io.github.amichne.kast.workspace.contract.WorkspaceResourceInitiationResult
import io.github.amichne.kast.workspace.contract.WorkspaceResourceObservation
import io.github.amichne.kast.workspace.contract.WorkspaceResourcePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkspaceResourceAdmissionControllerReviewRegressionTest {
    @Test
    fun `capacity waiter wakes when any same-kind initiation releases`() {
        val controller = controller()
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val thirdStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val first = executor.submit<WorkspaceResourceInitiationResult> {
                controller.coordinate(root("/one"), WorkspaceExpensiveWork.PROJECT_IMPORT) {
                    firstStarted.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            val second = executor.submit<WorkspaceResourceInitiationResult> {
                controller.coordinate(root("/two"), WorkspaceExpensiveWork.PROJECT_IMPORT) {
                    secondStarted.countDown()
                    assertTrue(releaseSecond.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            val third = executor.submit<WorkspaceResourceInitiationResult> {
                controller.coordinate(root("/three"), WorkspaceExpensiveWork.PROJECT_IMPORT) {
                    thirdStarted.countDown()
                }
            }
            assertTrue(awaitQueuedWaiter(controller))

            releaseSecond.countDown()
            assertTrue(second.get(2, TimeUnit.SECONDS) is WorkspaceResourceInitiationResult.Initiated)

            assertTrue(
                thirdStarted.await(2, TimeUnit.SECONDS),
                "A waiter must recheck capacity after the second active initiation releases",
            )
            assertEquals(1L, releaseFirst.count, "The first active initiation must still be running")
            assertTrue(third.get(2, TimeUnit.SECONDS) is WorkspaceResourceInitiationResult.Initiated)
            releaseFirst.countDown()
            assertTrue(first.get(2, TimeUnit.SECONDS) is WorkspaceResourceInitiationResult.Initiated)
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            executor.shutdownNow()
        }
    }

    private fun controller(): WorkspaceResourceAdmissionController =
        WorkspaceResourceAdmissionController(
            policy = WorkspaceResourcePolicy(
                runtimeStarts = limit(1),
                imports = limit(2),
                transitions = limit(1),
                indexing = limit(1),
                longOperations = limit(1),
                queuedWaiters = WorkspaceQueueLimit.parse(4).refined(),
                waitTimeout = WorkspaceAdmissionWaitMillis.parse(5_000).refined(),
                criticalHeap = WorkspaceCriticalHeapPercent.parse(90).refined(),
            ),
            observationAuthority = {
                WorkspaceResourceObservation(
                    heap = WorkspaceHeapUtilizationPercent.parse(20).refined(),
                    edt = WorkspaceEdtLiveness.Live,
                    activity = WorkspaceResourceActivity.none(),
                )
            },
        )

    private fun awaitQueuedWaiter(controller: WorkspaceResourceAdmissionController): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (controller.snapshot().queuedWaiters.value == 1) return true
            Thread.onSpinWait()
        }
        return false
    }

    private fun root(value: String): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(value)).refined()

    private fun limit(value: Int): WorkspaceConcurrencyLimit =
        WorkspaceConcurrencyLimit.parse(value).refined()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Unexpected fixture rejection: $failure")
    }
}
