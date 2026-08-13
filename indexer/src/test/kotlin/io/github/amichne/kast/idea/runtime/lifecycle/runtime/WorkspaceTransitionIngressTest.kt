package io.github.amichne.kast.idea

import io.github.amichne.kast.workspace.contract.WorkspaceLifecycle
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshness
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionSnapshot
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.PendingFileStage
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.spi.WorkspaceMutationAdmissionState
import io.github.amichne.kast.workspace.spi.WorkspaceMutationTransitionFailure
import io.github.amichne.kast.workspace.spi.WorkspaceMutationTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionOutcome
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.indexer.gradle.settlement.MonotonicClock
import io.github.amichne.kast.indexer.gradle.settlement.ProgressAwareFutureAwaiter
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressWaitPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.properties.Delegates

class WorkspaceTransitionIngressTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `workspace signal is queued before its worker wakeup`() {
        val order = mutableListOf<String>()
        routeWorkspaceSignal(
            lock = Any(),
            signal = WorkspaceSignal.Source,
            enqueue = { order += "queued:$it" },
            wake = { order += "wake:$it" },
        )
        assertEquals(
            listOf("queued:Source", "wake:Source"),
            order,
        )
    }

    @Test
    fun `reconciliation request returns only the next ready generation`() {
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(1))
        val next = testPublishedWorkspaceGeneration(
            generation = WorkspaceSemanticGeneration(2),
            identity = WorkspaceStateIdentity("next-workspace-state"),
        )
        val admission = readyAdmission(initial)
        val ingress = WorkspaceTransitionIngress(admission, testAwaiter {})
        ingress.bind { signal ->
            assertEquals(WorkspaceSignal.RecoveryAudit, signal)
            publish(admission, next)
            ingress.observe(readySnapshot(next))
        }
        val published = runBlocking {
            ingress.reconcile(WorkspaceTransitionRequest.Unkeyed(WorkspaceSignal.RecoveryAudit))
        }

        assertEquals(WorkspaceTransitionOutcome.Published(next.detachedPublication()), published)
    }

    @Test
    fun `source reconciliation enqueues freshness while sharing an active transition`() {
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(2))
        val next = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(3))
        val admission = readyAdmission(initial)
        val transitionRequested = AtomicBoolean(false)
        var ingress: WorkspaceTransitionIngress by Delegates.notNull()
        val awaiter = testAwaiter { elapsed ->
            if (elapsed == Duration.ofMillis(1)) {
                publish(admission, next)
                ingress.observe(readySnapshot(next))
            }
        }
        ingress = WorkspaceTransitionIngress(admission, awaiter)
        ingress.bind { transitionRequested.set(true) }
        admission.dirty("compatible source transition is active")
        ingress.observe(
            activeSnapshot(
                initial,
                WorkspaceLifecycle.Reconciling,
                TestTransitionEventCount.derive(2),
            ),
        )

        val published = runBlocking {
            ingress.reconcile(WorkspaceTransitionRequest.Unkeyed(WorkspaceSignal.Source))
        }

        assertEquals(WorkspaceTransitionOutcome.Published(next.detachedPublication()), published)
        assertTrue(transitionRequested.get())
    }

    @Test
    fun `covered source reconciliation returns publication that won the routing race`() =
        assertCoveredSourcePublicationRace(workspaceRoot)

    @Test
    fun `covered source reconciliation does not return a stale ready sample`() =
        assertCoveredSourceStaleReadyRace(workspaceRoot)

    @Test
    fun `covered source join retains a blocker observed before registration`() =
        assertCoveredSourceBlockedRegistrationRace(workspaceRoot)

    @Test
    fun `failed semantic admission outranks a stale active transition observation`() {
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(3))

        val route = WorkspaceTransitionRoute.derive(
            status = IdeaIndexSemanticAdmission.Status.Failed("semantic publication failed"),
            observation = TransitionObservation.Observed(
                activeSnapshot(
                    initial,
                    WorkspaceLifecycle.Reconciling,
                    TestTransitionEventCount.derive(3),
                ),
            ),
            request = WorkspaceTransitionRequest.Unkeyed(WorkspaceSignal.Source),
        )

        assertTrue(route is WorkspaceTransitionRoute.Rejected)
    }

    @Test
    fun `transition progress extends the no-progress deadline`() {
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(7))
        val next = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(8))
        val admission = readyAdmission(initial)
        var ingress: WorkspaceTransitionIngress by Delegates.notNull()
        val awaiter = testAwaiter { elapsed ->
            if (elapsed < Duration.ofMillis(5)) {
                ingress.observe(
                    activeSnapshot(
                        initial,
                        WorkspaceLifecycle.Reconciling,
                        observedEventCount = TestTransitionEventCount.derive(elapsed.toMillis()),
                    ),
                )
            } else {
                publish(admission, next)
                ingress.observe(readySnapshot(next))
            }
        }
        ingress = WorkspaceTransitionIngress(admission, awaiter)
        ingress.bind {}

        val published = runBlocking {
            ingress.reconcile(WorkspaceTransitionRequest.Unkeyed(WorkspaceSignal.Source))
        }

        assertEquals(WorkspaceTransitionOutcome.Published(next.detachedPublication()), published)
    }

    @Test
    fun `active indexing progress extends the deadline while the transition snapshot is unchanged`() {
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(9))
        val next = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(10))
        val admission = readyAdmission(initial)
        val indexingProgress = WorkspaceIndexingProgressAuthority()
        val activity = WorkspaceIndexingActivity.derive(testPendingSourceWork())
        var ingress: WorkspaceTransitionIngress by Delegates.notNull()
        val awaiter = testAwaiter { elapsed ->
            if (elapsed < Duration.ofMillis(5)) {
                indexingProgress.record(activity)
            } else {
                publish(admission, next)
                ingress.observe(readySnapshot(next))
            }
        }
        ingress = WorkspaceTransitionIngress(
            semanticAdmission = admission,
            transitionAwaiter = awaiter,
            indexingProgress = indexingProgress,
        )
        ingress.bind {}
        admission.dirty("active indexing pass")
        ingress.observe(
            activeSnapshot(
                generation = initial,
                lifecycle = WorkspaceLifecycle.Reconciling,
                observedEventCount = TestTransitionEventCount.derive(1),
            ),
        )

        val published = runBlocking {
            ingress.reconcile(WorkspaceTransitionRequest.Unkeyed(WorkspaceSignal.Source))
        }

        assertEquals(WorkspaceTransitionOutcome.Published(next.detachedPublication()), published)
    }

    @Test
    fun `workspace mutation completes only after its change is published`() {
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(4))
        val next = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(5))
        val admission = readyAdmission(initial)
        val ingress = WorkspaceTransitionIngress(admission, testAwaiter {})
        val order = mutableListOf<String>()
        ingress.bind {
            order += "signal"
            publish(admission, next)
            ingress.observe(readySnapshot(next))
            order += "published"
        }

        val result = runBlocking {
            ingress.mutate(WorkspaceSignal.Source, "test mutation") {
                order += "mutation"
                "result"
            }
        }

        assertEquals(
            WorkspaceMutationTransitionOutcome.Completed("result", next.detachedPublication()),
            result,
        )
        assertEquals(listOf("mutation", "signal", "published"), order)
        assertEquals(next, (admission.status() as IdeaIndexSemanticAdmission.Status.Ready).generation)
    }

    @Test
    fun `workspace movement before mutation admission returns a conflict without running the mutation`() {
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(6))
        val admission = readyAdmission(initial)
        val ingress = WorkspaceTransitionIngress(admission, testAwaiter {})
        val read = admission.openRead()
        val mutationStarted = CountDownLatch(1)
        val mutationRan = AtomicBoolean(false)
        val mutationOutcome = AtomicReference<WorkspaceMutationTransitionOutcome<Unit>>()
        val mutation = Thread {
            mutationStarted.countDown()
            mutationOutcome.set(
                runBlocking {
                    ingress.mutate(WorkspaceSignal.Source, "test moving mutation") {
                        mutationRan.set(true)
                    }
                },
            )
        }

        try {
            mutation.start()
            assertTrue(mutationStarted.await(1, TimeUnit.SECONDS))
            assertTrue(awaitCondition { admission.status() is IdeaIndexSemanticAdmission.Status.Pending })

            admission.dirty("source changed before mutation admission")
            read.close()
            mutation.join(1_000)

            assertFalse(mutation.isAlive)
            assertFalse(mutationRan.get())
            assertTrue(
                mutationOutcome.get() is WorkspaceMutationTransitionOutcome.Rejected,
            )
        } finally {
            read.close()
            mutation.interrupt()
            mutation.join(1_000)
            ingress.close()
        }
    }

    @Test
    fun `mutation outside ready returns typed conflict without running the mutation`() {
        val admission = IdeaIndexSemanticAdmission(workspaceTransitionProjectStub())
        val ingress = WorkspaceTransitionIngress(admission, testAwaiter {})
        val mutationRan = AtomicBoolean(false)

        val outcome = runBlocking {
            ingress.mutate(WorkspaceSignal.Source, "test non-ready mutation") {
                mutationRan.set(true)
            }
        }

        assertFalse(mutationRan.get())
        assertEquals(
            WorkspaceMutationTransitionOutcome.Rejected(
                WorkspaceMutationTransitionFailure.AdmissionUnavailable(
                    WorkspaceMutationAdmissionState.Pending,
                ),
            ),
            outcome,
        )
        ingress.close()
    }

    private fun readyAdmission(
        generation: PublishedWorkspaceGenerationManifest,
    ): IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(workspaceTransitionProjectStub()).also { admission ->
        val token = admission.beginReconciliation("test generation")
        check(
            admission.publishReady(token) { WorkspaceGenerationCommit(generation) } is
                IdeaIndexSemanticAdmission.ReadyPublication.Admitted,
        )
    }

    private fun publish(
        admission: IdeaIndexSemanticAdmission,
        generation: PublishedWorkspaceGenerationManifest,
    ) {
        admission.dirty("test transition")
        val token = admission.beginReconciliation("test reconciliation")
        check(
            admission.publishReady(token) { WorkspaceGenerationCommit(generation) } is
                IdeaIndexSemanticAdmission.ReadyPublication.Admitted,
        )
    }

    private fun readySnapshot(
        generation: PublishedWorkspaceGenerationManifest,
    ): WorkspaceTransitionSnapshot = WorkspaceTransitionSnapshot(
        lifecycle = WorkspaceLifecycle.Ready,
        pendingSignals = emptySet(),
        published = PublishedWorkspaceGenerationState.Published(generation.detachedPublication()),
        blocker = null,
        observedEventCount = generation.generation.value,
        activeSourceFreshness = WorkspaceSourceFreshness.Absent,
    )

    private fun activeSnapshot(
        generation: PublishedWorkspaceGenerationManifest,
        lifecycle: WorkspaceLifecycle,
        observedEventCount: TestTransitionEventCount,
    ): WorkspaceTransitionSnapshot = WorkspaceTransitionSnapshot(
        lifecycle = lifecycle,
        pendingSignals = emptySet(),
        published = PublishedWorkspaceGenerationState.Published(generation.detachedPublication()),
        blocker = null,
        observedEventCount = observedEventCount.value,
        activeSourceFreshness = WorkspaceSourceFreshness.Unkeyed,
    )

    private fun testAwaiter(onPause: (Duration) -> Unit): ProgressAwareFutureAwaiter {
        var elapsed = Duration.ZERO
        return ProgressAwareFutureAwaiter(
            policy = RuntimeProgressWaitPolicy.derive(
                noProgressTimeout = Duration.ofMillis(2),
                maximumWait = Duration.ofMillis(10),
                observationInterval = Duration.ofMillis(1),
            ),
            clock = MonotonicClock.fromRaw { elapsed.toNanos() },
            pause = { duration ->
                elapsed = elapsed.plus(duration)
                onPause(elapsed)
            },
        )
    }

    private fun testPendingSourceWork(): PendingFileStage {
        val root = Path.of("/workspace")
        val path = requireNotNull(
            SourceIndexFilePolicy.forWorkspace(root).sourcePath(root.resolve("src/Active.kt")),
        )
        return PendingFileStage(
            path = path,
            contentHash = FileContentHash.parse("a".repeat(64)),
            stage = FileIndexStage.SOURCE,
            version = FileStageVersions.CURRENT.source,
        )
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (!condition() && System.nanoTime() < deadline) Thread.onSpinWait()
        return condition()
    }

    @JvmInline
    private value class TestTransitionEventCount private constructor(val value: Long) {
        companion object {
            /** Proof transition: `Long -> TestTransitionEventCount`. */
            fun derive(value: Long): TestTransitionEventCount {
                require(value >= 0) { "Transition event count must not be negative" }
                return TestTransitionEventCount(value)
            }
        }
    }
}
