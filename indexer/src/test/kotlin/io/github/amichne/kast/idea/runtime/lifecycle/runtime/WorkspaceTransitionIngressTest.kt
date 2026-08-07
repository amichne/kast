package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.transition.WorkspaceLifecycle
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.idea.transition.WorkspaceTransitionSnapshot
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
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
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WorkspaceTransitionIngressTest {
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

        val published = runBlocking { ingress.reconcile(WorkspaceSignal.RecoveryAudit) }

        assertEquals(next, published)
    }

    @Test
    fun `source reconciliation joins a compatible transition already in progress`() {
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(2))
        val next = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(3))
        val admission = readyAdmission(initial)
        val transitionRequested = AtomicBoolean(false)
        lateinit var ingress: WorkspaceTransitionIngress
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

        val published = runBlocking { ingress.reconcile(WorkspaceSignal.Source) }

        assertEquals(next, published)
        assertFalse(transitionRequested.get())
    }

    @Test
    fun `failed semantic admission outranks a stale active transition observation`() {
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(3))

        val route = WorkspaceTransitionRoute.derive(
            signal = WorkspaceSignal.Source,
            status = IdeaIndexSemanticAdmission.Status.Failed("semantic publication failed"),
            observation = TransitionObservation.Observed(
                activeSnapshot(
                    initial,
                    WorkspaceLifecycle.Reconciling,
                    TestTransitionEventCount.derive(3),
                ),
            ),
        )

        assertTrue(route is WorkspaceTransitionRoute.Rejected)
    }

    @Test
    fun `transition progress extends the no-progress deadline`() {
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(7))
        val next = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(8))
        val admission = readyAdmission(initial)
        lateinit var ingress: WorkspaceTransitionIngress
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

        val published = runBlocking { ingress.reconcile(WorkspaceSignal.Source) }

        assertEquals(next, published)
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

        assertEquals("result", result)
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
        val mutationFailure = AtomicReference<Throwable>()
        val mutation = Thread {
            mutationStarted.countDown()
            runCatching {
                runBlocking {
                    ingress.mutate(WorkspaceSignal.Source, "test moving mutation") {
                        mutationRan.set(true)
                    }
                }
            }.onFailure(mutationFailure::set)
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
            assertTrue(mutationFailure.get() is ConflictException)
        } finally {
            read.close()
            mutation.interrupt()
            mutation.join(1_000)
            ingress.close()
        }
    }

    @Test
    fun `mutation outside ready returns typed conflict without running the mutation`() {
        val admission = IdeaIndexSemanticAdmission(projectStub())
        val ingress = WorkspaceTransitionIngress(admission, testAwaiter {})
        val mutationRan = AtomicBoolean(false)

        val failure = assertThrows(ConflictException::class.java) {
            runBlocking {
                ingress.mutate(WorkspaceSignal.Source, "test non-ready mutation") {
                    mutationRan.set(true)
                }
            }
        }

        assertFalse(mutationRan.get())
        assertTrue(
            failure.cause is IdeaIndexSemanticAdmission.WorkspaceMutationAdmissionUnavailableException,
        )
        ingress.close()
    }

    private fun readyAdmission(
        generation: PublishedWorkspaceGenerationManifest,
    ): IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(projectStub()).also { admission ->
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
        published = PublishedWorkspaceGenerationState.Published(generation),
        blocker = null,
        observedEventCount = generation.generation.value,
    )

    private fun activeSnapshot(
        generation: PublishedWorkspaceGenerationManifest,
        lifecycle: WorkspaceLifecycle,
        observedEventCount: TestTransitionEventCount,
    ): WorkspaceTransitionSnapshot = WorkspaceTransitionSnapshot(
        lifecycle = lifecycle,
        pendingSignals = emptySet(),
        published = PublishedWorkspaceGenerationState.Published(generation),
        blocker = null,
        observedEventCount = observedEventCount.value,
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

    private fun projectStub(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getName" -> "stub"
            "isDisposed" -> false
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "ProjectStub"
            else -> null
        }
    } as Project
}
