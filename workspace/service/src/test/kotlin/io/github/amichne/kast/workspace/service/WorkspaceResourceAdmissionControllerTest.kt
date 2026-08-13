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
import io.github.amichne.kast.workspace.contract.WorkspaceResourceAdmissionAction
import io.github.amichne.kast.workspace.contract.WorkspaceResourceActivity
import io.github.amichne.kast.workspace.contract.WorkspaceResourceBlocker
import io.github.amichne.kast.workspace.contract.WorkspaceResourceCount
import io.github.amichne.kast.workspace.contract.WorkspaceResourceInitiationResult
import io.github.amichne.kast.workspace.contract.WorkspaceResourceObservation
import io.github.amichne.kast.workspace.contract.WorkspaceResourcePolicy
import io.github.amichne.kast.workspace.spi.WorkspaceResourceObservationAuthority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WorkspaceResourceAdmissionControllerTest {
    @Test
    fun `concurrent exact-root demand performs one Gradle-style initiation and reuses it`() {
        val controller = controller()
        val root = root("/workspace")
        val begin = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gradleResolutions = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = List(8) {
                executor.submit<WorkspaceResourceInitiationResult> {
                    controller.coordinate(
                        root,
                        WorkspaceExpensiveWork.PROJECT_IMPORT,
                        WorkspaceResourceInitiation {
                            gradleResolutions.incrementAndGet()
                            begin.countDown()
                            assertTrue(release.await(2, TimeUnit.SECONDS))
                        },
                    )
                }
            }
            assertTrue(begin.await(2, TimeUnit.SECONDS))
            assertTrue(awaitLoad(controller, queuedWaiters = 7))
            release.countDown()

            val results = futures.map { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is WorkspaceResourceInitiationResult.Initiated })
            assertEquals(7, results.count { it is WorkspaceResourceInitiationResult.ReusedExactRoot })
            assertEquals(1, gradleResolutions.get())
            assertTrue(
                results.filterIsInstance<WorkspaceResourceInitiationResult.ReusedExactRoot>()
                    .all { it.timing.queue.nanoseconds > 0L },
            )
            assertEquals(controller.snapshot(), WorkspaceResourceControllerSnapshot.empty())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `conflicting roots queue while unrelated expensive kinds remain independent`() {
        val controller = controller()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val activeImports = AtomicInteger()
        val maximumImports = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<WorkspaceResourceInitiationResult> {
                controller.coordinate(
                    root("/one"),
                    WorkspaceExpensiveWork.PROJECT_IMPORT,
                    initiation(activeImports, maximumImports, firstStarted, releaseFirst),
                )
            }
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            val indexing = controller.coordinate(
                root("/indexing"),
                WorkspaceExpensiveWork.INDEXING,
                WorkspaceResourceInitiation {},
            )
            assertTrue(indexing is WorkspaceResourceInitiationResult.Initiated)

            val second = executor.submit<WorkspaceResourceInitiationResult> {
                controller.coordinate(
                    root("/two"),
                    WorkspaceExpensiveWork.PROJECT_IMPORT,
                    initiation(activeImports, maximumImports, secondStarted, CountDownLatch(0)),
                )
            }
            assertTrue(awaitLoad(controller, queuedWaiters = 1))
            assertEquals(1L, secondStarted.count)
            releaseFirst.countDown()

            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertTrue(first.get(2, TimeUnit.SECONDS) is WorkspaceResourceInitiationResult.Initiated)
            assertTrue(second.get(2, TimeUnit.SECONDS) is WorkspaceResourceInitiationResult.Initiated)
            assertEquals(1, maximumImports.get())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `heap EDT and each active resource return distinct blocker and recovery data`() {
        val observation = AtomicReference(healthyObservation())
        val controller = controller(observation = observation)
        val root = root("/workspace")

        observation.set(healthyObservation(heap = 95))
        assertRejected(
            controller.coordinate(root, WorkspaceExpensiveWork.RUNTIME_START) {},
            WorkspaceResourceBlocker.HeapCritical::class.java,
            WorkspaceResourceAdmissionAction.RECOVER_HEAP,
        )

        observation.set(healthyObservation(edt = WorkspaceEdtLiveness.Frozen))
        assertRejected(
            controller.coordinate(root, WorkspaceExpensiveWork.RUNTIME_START) {},
            WorkspaceResourceBlocker.EdtUnavailable::class.java,
            WorkspaceResourceAdmissionAction.RECOVER_EDT,
        )

        WorkspaceExpensiveWork.entries.forEach { kind ->
            observation.set(healthyObservation(activity = activity(kind)))
            val rejected = controller.coordinate(root, kind) {} as
                WorkspaceResourceInitiationResult.Rejected
            assertEquals(WorkspaceResourceBlocker.Capacity(kind, limit(1)), rejected.blocker)
            assertEquals(WorkspaceResourceAdmissionAction.RETRY_AFTER_RELEASE, rejected.action)
        }
    }

    @Test
    fun `queue pressure and absolute wait timeout are bounded`() {
        val controller = controller(queueLimit = 1, waitMillis = 100L)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val owner = executor.submit<WorkspaceResourceInitiationResult> {
                controller.coordinate(root("/workspace"), WorkspaceExpensiveWork.PROJECT_IMPORT) {
                    started.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val waiter = executor.submit<WorkspaceResourceInitiationResult> {
                controller.coordinate(root("/workspace"), WorkspaceExpensiveWork.PROJECT_IMPORT) {}
            }
            assertTrue(awaitLoad(controller, queuedWaiters = 1))

            val full = controller.coordinate(
                root("/workspace"),
                WorkspaceExpensiveWork.PROJECT_IMPORT,
            ) {} as WorkspaceResourceInitiationResult.Rejected
            assertTrue(full.blocker is WorkspaceResourceBlocker.QueueFull)
            assertEquals(WorkspaceResourceAdmissionAction.RETRY_AFTER_RELEASE, full.action)

            val timedOut = waiter.get(2, TimeUnit.SECONDS) as
                WorkspaceResourceInitiationResult.Rejected
            assertTrue(timedOut.blocker is WorkspaceResourceBlocker.WaitTimedOut)
            assertTrue(timedOut.timing.queue.nanoseconds > 0L)
            assertTrue(timedOut.timing.admission.nanoseconds >= 0L)

            release.countDown()
            assertTrue(owner.get(2, TimeUnit.SECONDS) is WorkspaceResourceInitiationResult.Initiated)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed initiation releases ownership before the exception escapes`() {
        val controller = controller()
        assertThrows(IllegalStateException::class.java) {
            controller.coordinate(root("/workspace"), WorkspaceExpensiveWork.PROJECT_IMPORT) {
                error("expected")
            }
        }
        assertEquals(WorkspaceResourceControllerSnapshot.empty(), controller.snapshot())
        assertTrue(
            controller.coordinate(
                root("/workspace"),
                WorkspaceExpensiveWork.PROJECT_IMPORT,
            ) {} is WorkspaceResourceInitiationResult.Initiated,
        )
    }

    private fun controller(
        queueLimit: Int = 16,
        waitMillis: Long = 2_000L,
        observation: AtomicReference<WorkspaceResourceObservation> =
            AtomicReference(healthyObservation()),
    ): WorkspaceResourceAdmissionController = WorkspaceResourceAdmissionController(
        policy = WorkspaceResourcePolicy(
            runtimeStarts = limit(1),
            imports = limit(1),
            transitions = limit(1),
            indexing = limit(1),
            longOperations = limit(1),
            queuedWaiters = WorkspaceQueueLimit.parse(queueLimit).refined(),
            waitTimeout = WorkspaceAdmissionWaitMillis.parse(waitMillis).refined(),
            criticalHeap = WorkspaceCriticalHeapPercent.parse(90).refined(),
        ),
        observationAuthority = WorkspaceResourceObservationAuthority(observation::get),
    )

    private fun healthyObservation(
        heap: Int = 20,
        edt: WorkspaceEdtLiveness = WorkspaceEdtLiveness.Live,
        activity: WorkspaceResourceActivity = WorkspaceResourceActivity.none(),
    ): WorkspaceResourceObservation = WorkspaceResourceObservation(
        heap = WorkspaceHeapUtilizationPercent.parse(heap).refined(),
        edt = edt,
        activity = activity,
    )

    private fun activity(kind: WorkspaceExpensiveWork): WorkspaceResourceActivity =
        WorkspaceResourceActivity.none().withActive(kind, count(1))

    private fun initiation(
        active: AtomicInteger,
        maximum: AtomicInteger,
        started: CountDownLatch,
        release: CountDownLatch,
    ): WorkspaceResourceInitiation = WorkspaceResourceInitiation {
        val current = active.incrementAndGet()
        maximum.accumulateAndGet(current, ::maxOf)
        started.countDown()
        try {
            assertTrue(release.await(2, TimeUnit.SECONDS))
        } finally {
            active.decrementAndGet()
        }
    }

    private fun assertRejected(
        result: WorkspaceResourceInitiationResult,
        blockerType: Class<out WorkspaceResourceBlocker>,
        action: WorkspaceResourceAdmissionAction,
    ) {
        val rejected = result as WorkspaceResourceInitiationResult.Rejected
        assertTrue(blockerType.isInstance(rejected.blocker))
        assertEquals(action, rejected.action)
    }

    private fun awaitLoad(
        controller: WorkspaceResourceAdmissionController,
        queuedWaiters: Int,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (controller.snapshot().queuedWaiters.value == queuedWaiters) return true
            Thread.onSpinWait()
        }
        return false
    }

    private fun root(value: String): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(value)).refined()

    private fun limit(value: Int): WorkspaceConcurrencyLimit =
        WorkspaceConcurrencyLimit.parse(value).refined()

    private fun count(value: Int): WorkspaceResourceCount =
        WorkspaceResourceCount.parse(value).refined()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
