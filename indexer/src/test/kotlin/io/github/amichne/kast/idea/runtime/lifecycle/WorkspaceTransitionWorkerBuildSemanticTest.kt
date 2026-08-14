package io.github.amichne.kast.idea

import io.github.amichne.kast.evidence.sqlite.detachedPublication
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.LinkedWorktreeLaunchClaim
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPublication
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionGuard
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionInspectionException
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionMarker
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionMarkerEvidence
import io.github.amichne.kast.idea.transition.GitWorktreeRegistrationProof
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionStatus
import io.github.amichne.kast.idea.transition.WorkspaceEventWakeup
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.indexer.gradle.bootstrap.readyInitialProjectModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class WorkspaceTransitionWorkerBuildSemanticTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `initial reconciliation reuses the imported Gradle model`() {
        val stableBuildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val refreshedSignals = mutableListOf<Set<WorkspaceSignal>>()
        val worker = worker(
            importedBuildInputs = stableBuildInputs,
            refreshWorkspace = refreshedSignals::add,
            candidateIdentity = { WorkspaceStateIdentity("initial-workspace") },
        )

        worker.requestInitialReconciliation()
        worker.run()

        assertEquals(
            listOf(setOf(WorkspaceSignal.InitialProjectModel)),
            refreshedSignals,
        )
    }

    @Test
    fun `initial reconciliation refreshes Gradle when build inputs moved after bootstrap`() {
        val importedBuildInputs = BuildSemanticInputIdentity("imported-build-inputs")
        val movedBuildInputs = BuildSemanticInputIdentity("moved-build-inputs")
        val refreshedSignals = mutableListOf<Set<WorkspaceSignal>>()
        val worker = worker(
            importedBuildInputs = importedBuildInputs,
            currentBuildInputs = movedBuildInputs,
            refreshWorkspace = refreshedSignals::add,
            candidateIdentity = { WorkspaceStateIdentity("initial-${it.value}") },
        )

        worker.requestInitialReconciliation()
        worker.run()

        assertEquals(
            listOf(setOf(WorkspaceSignal.InitialProjectModel, WorkspaceSignal.BuildSemantic)),
            refreshedSignals,
        )
    }

    @Test
    fun `missing linked-worktree Git directory does not block reconciliation`() {
        val repository = createCommittedTestRepository(tempDir)
        val workspace = tempDir.resolve("broken-linked-worktree")
        git(repository, "worktree", "add", "--detach", workspace.toString(), "HEAD")
        val gitDirectory = Path.of(gitOutput(workspace, "rev-parse", "--absolute-git-dir"))
            .toAbsolutePath()
            .normalize()
        val registrationProof = GitWorktreeRegistrationProof.capture(
            workspace,
            LinkedWorktreeLaunchClaim.of(workspace.resolve(".git"), gitDirectory),
        )
        Files.move(gitDirectory, tempDir.resolve("displaced-worktree-git-directory"))
        val stableBuildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val publications = mutableListOf<WorkspaceStateIdentity>()
        val failures = mutableListOf<Throwable>()
        val worker = worker(
            importedBuildInputs = stableBuildInputs,
            gitWorktreeTransitionGuard = GitWorktreeTransitionGuard.exactRoot(workspace, registrationProof),
            candidateIdentity = { WorkspaceStateIdentity("workspace-without-linked-git-directory") },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(onCommit = publications::add),
            onFailure = failures::add,
        )

        worker.observe(WorkspaceSignal.GitWorktree)
        worker.run()

        assertTrue(failures.isEmpty(), "missing linked-worktree metadata must not report a worker failure")
        assertEquals(
            listOf(WorkspaceStateIdentity("workspace-without-linked-git-directory")),
            publications,
        )
    }

    @Test
    fun `missing non-worktree Git directory remains blocked`() {
        val repository = createCommittedTestRepository(tempDir)
        val registeredWorktree = tempDir.resolve("registered-worktree")
        git(repository, "worktree", "add", "--detach", registeredWorktree.toString(), "HEAD")
        val workspace = tempDir.resolve("separate-git-directory-workspace").also(Files::createDirectories)
        val unregisteredDirectory = repository.resolve(".git/worktrees/unregistered")
        Files.writeString(workspace.resolve(".git"), "gitdir: $unregisteredDirectory")
        val stableBuildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val publications = mutableListOf<WorkspaceStateIdentity>()
        val failures = mutableListOf<Throwable>()
        val worker = worker(
            importedBuildInputs = stableBuildInputs,
            gitWorktreeTransitionGuard = GitWorktreeTransitionGuard.exactRoot(workspace),
            candidateIdentity = { WorkspaceStateIdentity("unavailable-git-directory") },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(onCommit = publications::add),
            onFailure = failures::add,
        )

        worker.observe(WorkspaceSignal.GitWorktree)
        worker.run()

        assertTrue(publications.isEmpty(), "unavailable non-worktree metadata must block publication")
        assertEquals(1, failures.size)
        assertTrue(failures.single() is GitWorktreeTransitionInspectionException)
    }

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

            override fun currency(
                manifest: io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest,
            ) = delegate.currency(manifest)

            override fun begin() = delegate.begin()

            override fun prepare(
                open: io.github.amichne.kast.evidence.contract.OpenWorkspacePublication,
                identity: WorkspaceStateIdentity,
                graphPublication: io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication,
            ) = delegate.prepare(open, identity, graphPublication).also {
                if (preparations.incrementAndGet() == 1) transition.set(inProgress)
            }

            override fun commit(prepared: io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication) =
                delegate.commit(prepared)

            override fun storedCommit(commit: io.github.amichne.kast.evidence.contract.WorkspacePublicationCommit) =
                delegate.storedCommit(commit)

            override fun discard(open: io.github.amichne.kast.evidence.contract.OpenWorkspacePublication) {
                discards.incrementAndGet()
                delegate.discard(open)
            }

            override fun discard(prepared: io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication) {
                discards.incrementAndGet()
                delegate.discard(prepared)
            }
        }
        val waits = mutableListOf<Long>()
        val worker = worker(
            importedBuildInputs = stableBuildInputs,
            eventWakeup = eventWakeup,
            gitWorktreeTransitionGuard = GitWorktreeTransitionGuard(transition::get),
            candidateIdentity = { WorkspaceStateIdentity("final-checkout-state") },
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
        val worker = worker(
            importedBuildInputs = stableBuildInputs,
            eventWakeup = eventWakeup,
            gitWorktreeTransitionGuard = GitWorktreeTransitionGuard(transition::get),
            refreshWorkspace = refreshedSignals::add,
            candidateIdentity = { WorkspaceStateIdentity("final-checkout-state") },
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
    fun `source wakeup refreshes Gradle when build inputs drifted without a build signal`() {
        val importedBuildInputs = BuildSemanticInputIdentity("imported-build-inputs")
        val currentBuildInputs = BuildSemanticInputIdentity("changed-build-inputs")
        val refreshedSignals = mutableListOf<Set<WorkspaceSignal>>()
        val publications = mutableListOf<WorkspaceStateIdentity>()
        val worker = worker(
            importedBuildInputs = importedBuildInputs,
            currentBuildInputs = currentBuildInputs,
            refreshWorkspace = refreshedSignals::add,
            candidateIdentity = { WorkspaceStateIdentity("state-${it.value}") },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(onCommit = publications::add),
        )

        worker.observe(WorkspaceSignal.Source)
        worker.run()

        assertEquals(
            listOf(setOf(WorkspaceSignal.Source, WorkspaceSignal.BuildSemantic)),
            refreshedSignals,
        )
        assertEquals(listOf(WorkspaceStateIdentity("state-changed-build-inputs")), publications)
    }

    @Test
    fun `completion carries the snapshot capability captured for the reconciled candidate`() {
        WorkspaceTransitionSnapshotPublicationScenario.verify()
    }

    private fun worker(
        importedBuildInputs: BuildSemanticInputIdentity,
        currentBuildInputs: BuildSemanticInputIdentity = importedBuildInputs,
        eventWakeup: WorkspaceEventWakeup = WorkspaceEventWakeup(),
        gitWorktreeTransitionGuard: GitWorktreeTransitionGuard = GitWorktreeTransitionGuard.stable(),
        refreshWorkspace: (Set<WorkspaceSignal>) -> Unit = {},
        candidateIdentity: (BuildSemanticInputIdentity) -> WorkspaceStateIdentity,
        workspaceGenerationPublication: WorkspaceGenerationPublication = TestWorkspaceGenerationPublication(),
        waitForNextPass: (Long) -> Boolean = { false },
        onFailure: (Throwable) -> Unit = { throw it },
    ) = WorkspaceTransitionWorker(
        initialConfig = KastConfig.defaults(),
        initialProjectModelAuthority = readyInitialProjectModel(importedBuildInputs),
        resolveBuildSemanticInputIdentity = { currentBuildInputs },
        semanticAdmission = IdeaIndexSemanticAdmission(workspaceTransitionProjectStub()),
        eventWakeup = eventWakeup,
        gitWorktreeTransitionGuard = gitWorktreeTransitionGuard,
        refreshWorkspace = refreshWorkspace,
        loadLiveConfig = { it },
        captureCandidate = { _, buildInputs -> unmanagedCandidate(candidateIdentity(buildInputs)) },
        runIndexingPass = { _, _, _ -> IndexingPassResult(KastSourceIndexSummary(), GraphLaneOutcome.Committed) },
        workspaceGenerationPublication = workspaceGenerationPublication,
        waitForNextPass = waitForNextPass,
        isCancelled = { false },
        onConfigFallback = {},
        onCompleted = {},
        onFailure = onFailure,
        onTransition = {},
    )

    private fun git(
        directory: Path,
        vararg arguments: String,
    ) {
        runGitCommand(directory, *arguments)
    }

    private fun gitOutput(
        directory: Path,
        vararg arguments: String,
    ): String =
        readGitOutput(directory, *arguments)

    private fun unmanagedCandidate(identity: WorkspaceStateIdentity) = WorkspaceReconciliationCandidate(
        identity = identity,
        indexingCandidate = null,
        snapshotPublication = RepositorySnapshotPublication.Unmanaged,
    )
}
