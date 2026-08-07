package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceIdentity
import io.github.amichne.kast.indexstore.snapshot.PublicationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.RepositoryOverlayPublication
import io.github.amichne.kast.indexstore.snapshot.SourceIndexSchemaVersion
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.properties.Delegates

class WorkspaceTransitionCoordinatorTest {
    @Test
    fun `relevant event withdraws readiness and one stable cycle publishes`() {
        val operations = RecordingOperations()
        val coordinator = WorkspaceTransitionCoordinator(operations)

        coordinator.observe(WorkspaceSignal.Source)

        assertEquals(WorkspaceLifecycle.Dirty, coordinator.snapshot().lifecycle)
        assertFalse(coordinator.snapshot().isReady)
        assertEquals(TransitionRun.Published, coordinator.reconcilePending())
        assertEquals(WorkspaceLifecycle.Ready, coordinator.snapshot().lifecycle)
        assertEquals(listOf(WorkspaceStateIdentity("state-1")), operations.published)
        assertEquals(1, operations.reconciliations.get())
    }

    @Test
    fun `ten thousand events conflate into bounded pending work`() {
        val operations = RecordingOperations()
        val coordinator = WorkspaceTransitionCoordinator(operations)

        repeat(10_000) { coordinator.observe(WorkspaceSignal.Source) }

        assertEquals(setOf(WorkspaceSignal.Source), coordinator.snapshot().pendingSignals)
        assertEquals(TransitionRun.Published, coordinator.reconcilePending())
        assertEquals(1, operations.reconciliations.get())
    }

    @Test
    fun `checkout changing one thousand paths publishes only one final cycle`() {
        val operations = RecordingOperations()
        val coordinator = WorkspaceTransitionCoordinator(operations)
        val checkoutSignals = listOf(
            WorkspaceSignal.Source,
            WorkspaceSignal.BuildSemantic,
            WorkspaceSignal.Configuration,
            WorkspaceSignal.Scope,
            WorkspaceSignal.GitWorktree,
        )

        repeat(1_000) { index -> coordinator.observe(checkoutSignals[index % checkoutSignals.size]) }

        assertEquals(checkoutSignals.toSet(), coordinator.snapshot().pendingSignals)
        assertEquals(TransitionRun.Published, coordinator.reconcilePending())
        assertEquals(1, operations.reconciliations.get())
        assertEquals(
            WorkspaceSemanticGeneration(1),
            (coordinator.snapshot().published as PublishedWorkspaceGenerationState.Published).manifest.generation,
        )
    }

    @Test
    fun `event during reconciliation discards candidate and schedules another cycle`() {
        var coordinator: WorkspaceTransitionCoordinator by Delegates.notNull()
        val operations = RecordingOperations(
            reconcile = {
                coordinator.observe(WorkspaceSignal.GitWorktree)
            },
        )
        coordinator = WorkspaceTransitionCoordinator(operations)
        coordinator.observe(WorkspaceSignal.Source)

        assertEquals(TransitionRun.Invalidated, coordinator.reconcilePending())
        assertTrue(operations.published.isEmpty())
        assertEquals(WorkspaceLifecycle.Dirty, coordinator.snapshot().lifecycle)
    }

    @Test
    fun `identity movement without an event discards candidate`() {
        val operations = RecordingOperations(
            identities = ArrayDeque(
                listOf(
                    WorkspaceStateIdentity("before"),
                    WorkspaceStateIdentity("after"),
                ),
            ),
        )
        val coordinator = WorkspaceTransitionCoordinator(operations)
        coordinator.observe(WorkspaceSignal.RecoveryAudit)

        assertEquals(TransitionRun.Invalidated, coordinator.reconcilePending())
        assertTrue(operations.published.isEmpty())
        assertEquals(WorkspaceLifecycle.Dirty, coordinator.snapshot().lifecycle)
    }

    @Test
    fun `unobserved identity movement during slow preparation cannot publish`() {
        val before = WorkspaceStateIdentity("before-preparation")
        val after = WorkspaceStateIdentity("after-preparation")
        val identities = ArrayDeque(listOf(before, before))
        val preparationStarted = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val commitCalls = AtomicInteger()
        val operations = RecordingOperations(
            identities = identities,
            onPrepare = {
                preparationStarted.countDown()
                releasePreparation.await()
            },
            onCommit = { manifest ->
                commitCalls.incrementAndGet()
                GenerationPublication.Published(WorkspaceGenerationCommit(manifest))
            },
        )
        val coordinator = WorkspaceTransitionCoordinator(operations)
        coordinator.observe(WorkspaceSignal.Source)
        val transitionResult = AtomicReference<TransitionRun>()
        val transition = thread { transitionResult.set(coordinator.reconcilePending()) }
        assertTrue(preparationStarted.await(1, TimeUnit.SECONDS), "generation preparation did not start")

        try {
            identities.addLast(after)
        } finally {
            releasePreparation.countDown()
            transition.join(1_000)
        }

        assertFalse(transition.isAlive)
        assertEquals(0, commitCalls.get())
        assertEquals(1, operations.discards.get())
        assertEquals(TransitionRun.Invalidated, transitionResult.get())
        assertEquals(WorkspaceLifecycle.Dirty, coordinator.snapshot().lifecycle)
        assertTrue(WorkspaceSignal.RecoveryAudit in coordinator.snapshot().pendingSignals)
    }

    @Test
    fun `build refresh failure blocks and preserves published generation`() {
        val previous = publishedManifest(7, "previous")
        val operations = RecordingOperations(refreshFailure = IllegalStateException("Gradle model unavailable"))
        val coordinator = WorkspaceTransitionCoordinator(
            operations,
            PublishedWorkspaceGenerationState.Published(previous),
        )
        coordinator.observe(WorkspaceSignal.BuildSemantic)

        assertEquals(TransitionRun.Blocked, coordinator.reconcilePending())
        val snapshot = coordinator.snapshot()
        assertEquals(WorkspaceLifecycle.Blocked, snapshot.lifecycle)
        assertEquals(PublishedWorkspaceGenerationState.Published(previous), snapshot.published)
        assertEquals("Gradle model unavailable", snapshot.blocker?.detail)
    }

    @Test
    fun `committed database publication becomes ready`() {
        val operations = RecordingOperations()
        val coordinator = WorkspaceTransitionCoordinator(operations)
        coordinator.observe(WorkspaceSignal.Source)

        assertEquals(TransitionRun.Published, coordinator.reconcilePending())
        val snapshot = coordinator.snapshot()
        assertEquals(WorkspaceLifecycle.Ready, snapshot.lifecycle)
        assertEquals(
            WorkspaceSemanticGeneration(1),
            (snapshot.published as PublishedWorkspaceGenerationState.Published).manifest.generation,
        )
        assertEquals(null, snapshot.blocker)
    }

    @Test
    fun `event during slow generation preparation prevents pointer commit`() {
        val preparationStarted = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val observationCompleted = CountDownLatch(1)
        val commitCalls = AtomicInteger()
        val operations = RecordingOperations(
            onPrepare = {
                preparationStarted.countDown()
                releasePreparation.await()
            },
            onCommit = { manifest ->
                commitCalls.incrementAndGet()
                GenerationPublication.Published(WorkspaceGenerationCommit(manifest))
            },
        )
        val coordinator = WorkspaceTransitionCoordinator(operations)
        coordinator.observe(WorkspaceSignal.Source)
        val transitionResult = AtomicReference<TransitionRun>()
        val transition = thread { transitionResult.set(coordinator.reconcilePending()) }
        assertTrue(preparationStarted.await(1, TimeUnit.SECONDS), "generation preparation did not start")
        val observer = thread {
            coordinator.observe(WorkspaceSignal.GitWorktree)
            observationCompleted.countDown()
        }

        try {
            assertTrue(
                observationCompleted.await(1, TimeUnit.SECONDS),
                "workspace invalidation must not wait for generation preparation",
            )
        } finally {
            releasePreparation.countDown()
            transition.join(1_000)
            observer.join(1_000)
        }

        assertEquals(0, commitCalls.get())
        assertEquals(TransitionRun.Invalidated, transitionResult.get())
        assertEquals(WorkspaceLifecycle.Dirty, coordinator.snapshot().lifecycle)
    }

    @Test
    fun `event during pointer commit withdraws readiness without waiting`() {
        val previous = publishedManifest(7, "previous")
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val observationCompleted = CountDownLatch(1)
        val operations = RecordingOperations(
            onCommit = { manifest ->
                commitStarted.countDown()
                releaseCommit.await()
                GenerationPublication.Published(WorkspaceGenerationCommit(manifest))
            },
        )
        val coordinator = WorkspaceTransitionCoordinator(
            operations,
            PublishedWorkspaceGenerationState.Published(previous),
        )
        coordinator.observe(WorkspaceSignal.Source)
        val transitionResult = AtomicReference<TransitionRun>()
        val transition = thread { transitionResult.set(coordinator.reconcilePending()) }
        assertTrue(commitStarted.await(1, TimeUnit.SECONDS), "generation commit did not start")
        val observer = thread {
            coordinator.observe(WorkspaceSignal.Source)
            observationCompleted.countDown()
        }

        try {
            assertTrue(
                observationCompleted.await(1, TimeUnit.SECONDS),
                "workspace invalidation must not wait for the current-pointer commit",
            )
        } finally {
            releaseCommit.countDown()
            transition.join(1_000)
            observer.join(1_000)
        }

        assertEquals(TransitionRun.Invalidated, transitionResult.get())
        assertEquals(WorkspaceLifecycle.Dirty, coordinator.snapshot().lifecycle)
        assertEquals(
            PublishedWorkspaceGenerationState.Published(previous),
            coordinator.snapshot().published,
        )
    }
}

private class RecordingOperations(
    private val identities: ArrayDeque<WorkspaceStateIdentity> = ArrayDeque(),
    private val reconcile: () -> Unit = {},
    private val refreshFailure: Throwable? = null,
    private val onPrepare: (WorkspaceStateIdentity) -> Unit = {},
    private val onCommit: (PublishedWorkspaceGenerationManifest) -> GenerationPublication =
        { manifest -> GenerationPublication.Published(WorkspaceGenerationCommit(manifest)) },
) : WorkspaceTransitionOperations {
    val reconciliations = AtomicInteger()
    val published = mutableListOf<WorkspaceStateIdentity>()
    val discards = AtomicInteger()
    private val nextGeneration = AtomicInteger()

    override fun settle(signals: Set<WorkspaceSignal>) = Unit

    override fun refresh(signals: Set<WorkspaceSignal>) {
        refreshFailure?.let { throw it }
    }

    override fun captureIdentity(): WorkspaceStateIdentity =
        identities.removeFirstOrNull() ?: WorkspaceStateIdentity("state-1")

    override fun reconcile(candidate: WorkspaceStateIdentity): WorkspaceStateIdentity {
        reconciliations.incrementAndGet()
        reconcile()
        return candidate
    }

    override fun beginPublication(): OpenWorkspacePublication = RecordingOpenWorkspacePublication

    override fun preparePublication(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
    ): PreparedWorkspacePublication {
        check(open === RecordingOpenWorkspacePublication)
        onPrepare(identity)
        return RecordingPreparedWorkspacePublication(identity)
    }

    override fun commitPublication(prepared: PreparedWorkspacePublication): GenerationPublication {
        val identity = (prepared as RecordingPreparedWorkspacePublication).identity
        val manifest = publishedManifest(nextGeneration.incrementAndGet().toLong(), identity.value)
        return onCommit(manifest).also { publication ->
            if (publication is GenerationPublication.Published) published += identity
        }
    }

    override fun discardPublication(open: OpenWorkspacePublication) {
        discards.incrementAndGet()
    }

    override fun discardPublication(prepared: PreparedWorkspacePublication) {
        discards.incrementAndGet()
    }
}

private data object RecordingOpenWorkspacePublication : OpenWorkspacePublication

private class RecordingPreparedWorkspacePublication(
    val identity: WorkspaceStateIdentity,
) : PreparedWorkspacePublication

private fun publishedManifest(
    generation: Long,
    identity: String,
): PublishedWorkspaceGenerationManifest = PublishedWorkspaceGenerationManifest(
    generation = WorkspaceSemanticGeneration(generation),
    identity = PublishedWorkspaceIdentity(identity),
    sourceIndexGeneration = SourceIndexGeneration(generation),
    sourceIndexSchemaVersion = SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION),
    publishedAt = PublicationEpochMillis.fromClock(1),
    repositoryOverlay = RepositoryOverlayPublication.ABSENT,
)
