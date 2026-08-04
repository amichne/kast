package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionGuard
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionMarker
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionMarkerEvidence
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionStatus
import io.github.amichne.kast.idea.transition.WorkspaceEventWakeup
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class WorkspaceTransitionWorkerBuildSemanticTest {
    @Test
    fun `checkout starting after refresh cannot cross the publication boundary`() {
        val stableBuildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val clock = AtomicLong()
        val eventWakeup = WorkspaceEventWakeup(
            nanoTime = clock::get,
            awaitCondition = { _, remainingNanos ->
                clock.addAndGet(remainingNanos)
                0L
            },
        )
        val inProgress = GitWorktreeTransitionStatus.InProgress(
            setOf(
                GitWorktreeTransitionMarkerEvidence(
                    GitWorktreeTransitionMarker.INDEX_LOCK,
                    Path.of("/git/worktrees/exact/index.lock"),
                ),
            ),
        )
        val transition = AtomicReference<GitWorktreeTransitionStatus>(GitWorktreeTransitionStatus.Stable)
        val publications = CopyOnWriteArrayList<WorkspaceStateIdentity>()
        val delegate = TestWorkspaceGenerationPublication(onCommit = publications::add)
        val preparations = AtomicInteger()
        val discards = AtomicInteger()
        val publication = object : WorkspaceGenerationPublication {
            override fun current() = delegate.current()

            override fun prepare(identity: WorkspaceStateIdentity) = delegate.prepare(identity).also {
                if (preparations.incrementAndGet() == 1) transition.set(inProgress)
            }

            override fun commit(prepared: io.github.amichne.kast.idea.transition.PreparedWorkspacePublication) =
                delegate.commit(prepared)

            override fun discard(prepared: io.github.amichne.kast.idea.transition.PreparedWorkspacePublication) {
                discards.incrementAndGet()
                delegate.discard(prepared)
            }
        }
        val waits = mutableListOf<Long>()
        val worker = WorkspaceTransitionWorker(
            initialConfig = KastConfig.defaults(),
            initialModelBuildSemanticIdentity = stableBuildInputs,
            resolveBuildSemanticInputIdentity = { stableBuildInputs },
            semanticAdmission = IdeaIndexSemanticAdmission(projectStub()),
            eventWakeup = eventWakeup,
            gitWorktreeTransitionGuard = GitWorktreeTransitionGuard(transition::get),
            refreshWorkspace = {},
            loadLiveConfig = { it },
            captureCandidate = { _, _ ->
                WorkspaceReconciliationCandidate(
                    identity = WorkspaceStateIdentity("final-checkout-state"),
                    indexingCandidate = null,
                )
            },
            runIndexingPass = { _, _, _ -> IndexingPassResult(KastSourceIndexSummary(), graphFailure = null) },
            workspaceGenerationPublication = publication,
            waitForNextPass = { delayMillis ->
                waits += delayMillis
                if (waits.size == 1) {
                    assertTrue(publications.isEmpty(), "an active checkout crossed the publication boundary")
                    transition.set(GitWorktreeTransitionStatus.Stable)
                    true
                } else {
                    false
                }
            },
            isCancelled = { false },
            onConfigFallback = {},
            onCompleted = {},
            onFailure = { throw it },
            onTransition = {},
        )

        worker.observe(WorkspaceSignal.GitWorktree)
        eventWakeup.signal(WorkspaceSignal.GitWorktree)
        worker.run()

        assertEquals(listOf(250L, 300_000L), waits)
        assertEquals(2, preparations.get())
        assertEquals(1, discards.get())
        assertEquals(listOf(WorkspaceStateIdentity("final-checkout-state")), publications)
    }

    @Test
    fun `streamed checkout cannot publish across a quiet gap until its exact Git transition clears`() {
        val stableBuildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val clock = AtomicLong()
        val eventWakeup = WorkspaceEventWakeup(
            nanoTime = clock::get,
            awaitCondition = { _, remainingNanos ->
                clock.addAndGet(remainingNanos)
                0L
            },
        )
        val transition = AtomicReference<GitWorktreeTransitionStatus>(
            GitWorktreeTransitionStatus.InProgress(
                setOf(
                    GitWorktreeTransitionMarkerEvidence(
                        GitWorktreeTransitionMarker.INDEX_LOCK,
                        Path.of("/git/worktrees/exact/index.lock"),
                    ),
                ),
            ),
        )
        val publications = CopyOnWriteArrayList<WorkspaceStateIdentity>()
        val refreshedSignals = CopyOnWriteArrayList<Set<WorkspaceSignal>>()
        val firstRetry = CountDownLatch(1)
        val secondRetry = CountDownLatch(1)
        val releaseFirstRetry = CountDownLatch(1)
        val releaseSecondRetry = CountDownLatch(1)
        val waits = CopyOnWriteArrayList<Long>()
        val waitCount = AtomicInteger()
        val worker = WorkspaceTransitionWorker(
            initialConfig = KastConfig.defaults(),
            initialModelBuildSemanticIdentity = stableBuildInputs,
            resolveBuildSemanticInputIdentity = { stableBuildInputs },
            semanticAdmission = IdeaIndexSemanticAdmission(projectStub()),
            eventWakeup = eventWakeup,
            gitWorktreeTransitionGuard = GitWorktreeTransitionGuard(transition::get),
            refreshWorkspace = refreshedSignals::add,
            loadLiveConfig = { it },
            captureCandidate = { _, _ ->
                WorkspaceReconciliationCandidate(
                    identity = WorkspaceStateIdentity("final-checkout-state"),
                    indexingCandidate = null,
                )
            },
            runIndexingPass = { _, _, _ -> IndexingPassResult(KastSourceIndexSummary(), graphFailure = null) },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(onCommit = publications::add),
            waitForNextPass = { delayMillis ->
                waits += delayMillis
                when (waitCount.incrementAndGet()) {
                    1 -> {
                        firstRetry.countDown()
                        releaseFirstRetry.await(1, TimeUnit.SECONDS)
                    }

                    2 -> {
                        secondRetry.countDown()
                        releaseSecondRetry.await(1, TimeUnit.SECONDS)
                    }

                    else -> false
                }
            },
            isCancelled = { false },
            onConfigFallback = {},
            onCompleted = {},
            onFailure = { throw it },
            onTransition = {},
        )

        fun stream(signal: WorkspaceSignal) {
            worker.observe(signal)
            eventWakeup.signal(signal)
        }

        stream(WorkspaceSignal.GitWorktree)
        val running = thread(isDaemon = true, block = worker::run)
        try {
            assertTrue(firstRetry.await(1, TimeUnit.SECONDS), "checkout guard did not request its first retry")
            repeat(1_000) { stream(WorkspaceSignal.Source) }
            releaseFirstRetry.countDown()
            assertTrue(secondRetry.await(1, TimeUnit.SECONDS), "quiet checkout gap did not request another retry")

            assertTrue(publications.isEmpty(), "an intermediate checkout state became current")
            assertTrue(refreshedSignals.isEmpty(), "an active checkout reached refresh")

            transition.set(GitWorktreeTransitionStatus.Stable)
            releaseSecondRetry.countDown()
            running.join(1_000)

            assertFalse(running.isAlive)
            assertEquals(listOf(250L, 250L, 300_000L), waits)
            assertEquals(listOf(setOf(WorkspaceSignal.GitWorktree, WorkspaceSignal.Source)), refreshedSignals)
            assertEquals(listOf(WorkspaceStateIdentity("final-checkout-state")), publications)
        } finally {
            releaseFirstRetry.countDown()
            releaseSecondRetry.countDown()
            running.interrupt()
            running.join(1_000)
        }
    }

    @Test
    fun `recovery audit repairs a workspace change with no event`() {
        val stableBuildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val refreshedSignals = mutableListOf<Set<WorkspaceSignal>>()
        val publications = mutableListOf<WorkspaceStateIdentity>()
        var waitCount = 0
        val worker = WorkspaceTransitionWorker(
            initialConfig = KastConfig.defaults(),
            initialModelBuildSemanticIdentity = stableBuildInputs,
            resolveBuildSemanticInputIdentity = { stableBuildInputs },
            semanticAdmission = IdeaIndexSemanticAdmission(projectStub()),
            eventWakeup = WorkspaceEventWakeup(),
            refreshWorkspace = refreshedSignals::add,
            loadLiveConfig = { it },
            captureCandidate = { _, _ ->
                WorkspaceReconciliationCandidate(
                    identity = WorkspaceStateIdentity("missed-change"),
                    indexingCandidate = null,
                )
            },
            runIndexingPass = { _, _, _ -> IndexingPassResult(KastSourceIndexSummary(), graphFailure = null) },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(onCommit = publications::add),
            waitForNextPass = { waitCount++ == 0 },
            isCancelled = { false },
            onConfigFallback = {},
            onCompleted = {},
            onFailure = { throw it },
            onTransition = {},
        )

        worker.run()

        assertEquals(
            listOf(setOf(WorkspaceSignal.RecoveryAudit, WorkspaceSignal.BuildSemantic)),
            refreshedSignals,
        )
        assertEquals(listOf(WorkspaceStateIdentity("missed-change")), publications)
        assertEquals(2, waitCount)
    }

    @Test
    fun `source wakeup refreshes Gradle when build inputs drifted without a build signal`() {
        val importedBuildInputs = BuildSemanticInputIdentity("imported-build-inputs")
        val currentBuildInputs = BuildSemanticInputIdentity("changed-build-inputs")
        val refreshedSignals = mutableListOf<Set<WorkspaceSignal>>()
        val publications = mutableListOf<WorkspaceStateIdentity>()
        val worker = WorkspaceTransitionWorker(
            initialConfig = KastConfig.defaults(),
            initialModelBuildSemanticIdentity = importedBuildInputs,
            resolveBuildSemanticInputIdentity = { currentBuildInputs },
            semanticAdmission = IdeaIndexSemanticAdmission(projectStub()),
            eventWakeup = WorkspaceEventWakeup(),
            refreshWorkspace = refreshedSignals::add,
            loadLiveConfig = { it },
            captureCandidate = { _, buildInputs ->
                WorkspaceReconciliationCandidate(
                    identity = WorkspaceStateIdentity("state-${buildInputs.value}"),
                    indexingCandidate = null,
                )
            },
            runIndexingPass = { _, _, _ -> IndexingPassResult(KastSourceIndexSummary(), graphFailure = null) },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(onCommit = publications::add),
            waitForNextPass = { false },
            isCancelled = { false },
            onConfigFallback = {},
            onCompleted = {},
            onFailure = { throw it },
            onTransition = {},
        )

        worker.observe(WorkspaceSignal.Source)
        worker.run()

        assertEquals(
            listOf(setOf(WorkspaceSignal.Source, WorkspaceSignal.BuildSemantic)),
            refreshedSignals,
        )
        assertEquals(listOf(WorkspaceStateIdentity("state-changed-build-inputs")), publications)
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
