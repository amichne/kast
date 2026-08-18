package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.evidence.contract.GenerationPublication
import io.github.amichne.kast.evidence.contract.OpenWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationAuthority
import io.github.amichne.kast.evidence.contract.WorkspacePublicationCommit
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.TransitionBlockerKind
import io.github.amichne.kast.workspace.contract.TransitionRun
import io.github.amichne.kast.workspace.contract.WorkspaceLifecycle
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshness
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshnessClaim
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshnessClaims
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionFailureClassifier
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionFailureDisposition
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest
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
        val recording = RecordingTransition()
        val coordinator = coordinator(recording)

        coordinator.observe(WorkspaceSignal.Source)

        assertEquals(WorkspaceLifecycle.Dirty, coordinator.snapshot().lifecycle)
        assertFalse(coordinator.snapshot().isReady)
        assertEquals(TransitionRun.Published, coordinator.reconcilePending())
        assertEquals(WorkspaceLifecycle.Ready, coordinator.snapshot().lifecycle)
        assertEquals(listOf(identity("state-1")), recording.published)
        assertEquals(1, recording.reconciliations.get())
    }

    @Test
    fun `ten thousand events conflate into one bounded cycle`() {
        val recording = RecordingTransition()
        val coordinator = coordinator(recording)

        repeat(10_000) { coordinator.observe(WorkspaceSignal.Source) }

        assertEquals(setOf(WorkspaceSignal.Source), coordinator.snapshot().pendingSignals)
        assertEquals(TransitionRun.Published, coordinator.reconcilePending())
        assertEquals(1, recording.reconciliations.get())
    }

    @Test
    fun `active cycle publishes its exact source freshness claims`() {
        val request = sourceRequest()
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val recording = RecordingTransition(
            onRefresh = {
                refreshStarted.countDown()
                releaseRefresh.await()
            },
        )
        val coordinator = coordinator(recording)
        coordinator.observe(request)
        val transition = thread { coordinator.reconcilePending() }

        try {
            assertTrue(refreshStarted.await(1, TimeUnit.SECONDS), "source refresh did not start")
            assertEquals(
                WorkspaceSourceFreshness.Claimed(request.claims),
                coordinator.snapshot().activeSourceFreshness,
            )
        } finally {
            releaseRefresh.countDown()
            transition.join(1_000)
        }
        assertFalse(transition.isAlive)
    }

    @Test
    fun `checkout signals conflate and publish one final cycle`() {
        val recording = RecordingTransition()
        val coordinator = coordinator(recording)
        val signals = listOf(
            WorkspaceSignal.Source,
            WorkspaceSignal.BuildSemantic,
            WorkspaceSignal.Configuration,
            WorkspaceSignal.Scope,
            WorkspaceSignal.GitWorktree,
        )

        repeat(1_000) { index -> coordinator.observe(signals[index % signals.size]) }

        assertEquals(signals.toSet(), coordinator.snapshot().pendingSignals)
        assertEquals(TransitionRun.Published, coordinator.reconcilePending())
        assertEquals(1, recording.reconciliations.get())
        assertEquals(generation(1), coordinator.snapshot().published.publication().generation)
    }

    @Test
    fun `event during reconciliation discards candidate and schedules another cycle`() {
        var coordinator: WorkspaceTransitionCoordinator by Delegates.notNull()
        val recording = RecordingTransition(
            reconcile = { coordinator.observe(WorkspaceSignal.GitWorktree) },
        )
        coordinator = coordinator(recording)
        coordinator.observe(WorkspaceSignal.Source)

        assertEquals(TransitionRun.Invalidated, coordinator.reconcilePending())
        assertTrue(recording.published.isEmpty())
        assertEquals(WorkspaceLifecycle.Dirty, coordinator.snapshot().lifecycle)
    }

    @Test
    fun `identity movement without an event discards candidate`() {
        val recording = RecordingTransition(
            identities = ArrayDeque(listOf(identity("before"), identity("after"))),
        )
        val coordinator = coordinator(recording)
        coordinator.observe(WorkspaceSignal.RecoveryAudit)

        assertEquals(TransitionRun.Invalidated, coordinator.reconcilePending())
        assertTrue(recording.published.isEmpty())
        assertEquals(WorkspaceLifecycle.Dirty, coordinator.snapshot().lifecycle)
    }

    @Test
    fun `unobserved identity movement during preparation cannot publish`() {
        val before = identity("before-preparation")
        val identities = ArrayDeque(listOf(before, before))
        val preparationStarted = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val commitCalls = AtomicInteger()
        val recording = RecordingTransition(
            identities = identities,
            onPrepare = {
                preparationStarted.countDown()
                releasePreparation.await()
            },
            onCommit = { publication ->
                commitCalls.incrementAndGet()
                GenerationPublication.Published(RecordingCommit(publication))
            },
        )
        val coordinator = coordinator(recording)
        coordinator.observe(WorkspaceSignal.Source)
        val outcome = AtomicReference<TransitionRun>()
        val transition = thread { outcome.set(coordinator.reconcilePending()) }
        assertTrue(preparationStarted.await(1, TimeUnit.SECONDS))

        try {
            identities.addLast(identity("after-preparation"))
        } finally {
            releasePreparation.countDown()
            transition.join(1_000)
        }

        assertFalse(transition.isAlive)
        assertEquals(0, commitCalls.get())
        assertEquals(1, recording.discards.get())
        assertEquals(TransitionRun.Invalidated, outcome.get())
        assertTrue(WorkspaceSignal.RecoveryAudit in coordinator.snapshot().pendingSignals)
    }

    @Test
    fun `refresh failure blocks and preserves published generation`() {
        val previous = publication(7, "previous")
        val recording = RecordingTransition(
            initial = PublishedWorkspaceGenerationState.Published(previous),
            refreshFailure = IllegalStateException("Gradle model unavailable"),
        )
        val coordinator = coordinator(recording)
        coordinator.observe(WorkspaceSignal.BuildSemantic)

        assertEquals(TransitionRun.Blocked, coordinator.reconcilePending())
        assertEquals(WorkspaceLifecycle.Blocked, coordinator.snapshot().lifecycle)
        assertEquals(PublishedWorkspaceGenerationState.Published(previous), coordinator.snapshot().published)
        assertEquals("Gradle model unavailable", coordinator.snapshot().blocker?.detail)
    }

    @Test
    fun `event during preparation prevents commit without blocking observation`() {
        val preparationStarted = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val observationCompleted = CountDownLatch(1)
        val commitCalls = AtomicInteger()
        val recording = RecordingTransition(
            onPrepare = {
                preparationStarted.countDown()
                releasePreparation.await()
            },
            onCommit = { publication ->
                commitCalls.incrementAndGet()
                GenerationPublication.Published(RecordingCommit(publication))
            },
        )
        val coordinator = coordinator(recording)
        coordinator.observe(WorkspaceSignal.Source)
        val outcome = AtomicReference<TransitionRun>()
        val transition = thread { outcome.set(coordinator.reconcilePending()) }
        assertTrue(preparationStarted.await(1, TimeUnit.SECONDS))
        val observer = thread {
            coordinator.observe(WorkspaceSignal.GitWorktree)
            observationCompleted.countDown()
        }

        try {
            assertTrue(observationCompleted.await(1, TimeUnit.SECONDS))
        } finally {
            releasePreparation.countDown()
            transition.join(1_000)
            observer.join(1_000)
        }

        assertEquals(0, commitCalls.get())
        assertEquals(TransitionRun.Invalidated, outcome.get())
    }

    @Test
    fun `event during commit withdraws readiness without waiting`() {
        val previous = publication(7, "previous")
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val observationCompleted = CountDownLatch(1)
        val recording = RecordingTransition(
            initial = PublishedWorkspaceGenerationState.Published(previous),
            onCommit = { next ->
                commitStarted.countDown()
                releaseCommit.await()
                GenerationPublication.Published(RecordingCommit(next))
            },
        )
        val coordinator = coordinator(recording)
        coordinator.observe(WorkspaceSignal.Source)
        val outcome = AtomicReference<TransitionRun>()
        val transition = thread { outcome.set(coordinator.reconcilePending()) }
        assertTrue(commitStarted.await(1, TimeUnit.SECONDS))
        val observer = thread {
            coordinator.observe(WorkspaceSignal.Source)
            observationCompleted.countDown()
        }

        try {
            assertTrue(observationCompleted.await(1, TimeUnit.SECONDS))
        } finally {
            releaseCommit.countDown()
            transition.join(1_000)
            observer.join(1_000)
        }

        assertEquals(TransitionRun.Invalidated, outcome.get())
        assertEquals(PublishedWorkspaceGenerationState.Published(previous), coordinator.snapshot().published)
    }
}

private class RecordingTransition(
    private val identities: ArrayDeque<WorkspaceStateIdentity> = ArrayDeque(),
    private val reconcile: () -> Unit = {},
    private val refreshFailure: Throwable? = null,
    private val onRefresh: () -> Unit = {},
    private val onPrepare: (WorkspaceStateIdentity) -> Unit = {},
    private val onCommit: (PublishedWorkspaceGeneration) -> GenerationPublication = { publication ->
        GenerationPublication.Published(RecordingCommit(publication))
    },
    private val initial: PublishedWorkspaceGenerationState = PublishedWorkspaceGenerationState.Unpublished,
) : WorkspaceTransitionOperations, WorkspacePublicationAuthority {
    val reconciliations = AtomicInteger()
    val published = mutableListOf<WorkspaceStateIdentity>()
    val discards = AtomicInteger()
    private val nextGeneration = AtomicInteger()

    override fun settle(signals: Set<WorkspaceSignal>) = Unit

    override fun refresh(signals: Set<WorkspaceSignal>) {
        onRefresh()
        refreshFailure?.let { throw it }
    }

    override fun captureIdentity(): WorkspaceStateIdentity =
        identities.removeFirstOrNull() ?: identity("state-1")

    override fun reconcile(candidate: WorkspaceStateIdentity): WorkspaceStateIdentity {
        reconciliations.incrementAndGet()
        reconcile()
        return candidate
    }

    override fun current(): PublishedWorkspaceGenerationState = initial

    override fun begin(): OpenWorkspacePublication = RecordingOpen

    override fun prepare(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ): PreparedWorkspacePublication {
        check(open === RecordingOpen)
        onPrepare(identity)
        return RecordingPrepared(identity)
    }

    override fun commit(prepared: PreparedWorkspacePublication): GenerationPublication {
        val identity = (prepared as RecordingPrepared).identity
        return onCommit(publication(nextGeneration.incrementAndGet().toLong(), identity.value))
            .also { outcome ->
                if (outcome is GenerationPublication.Published) published += identity
            }
    }

    override fun discard(open: OpenWorkspacePublication) {
        discards.incrementAndGet()
    }

    override fun discard(prepared: PreparedWorkspacePublication) {
        discards.incrementAndGet()
    }
}

private data object RecordingOpen : OpenWorkspacePublication

private data class RecordingPrepared(
    val identity: WorkspaceStateIdentity,
) : PreparedWorkspacePublication

private data class RecordingCommit(
    override val publication: PublishedWorkspaceGeneration,
) : WorkspacePublicationCommit

private fun coordinator(recording: RecordingTransition): WorkspaceTransitionCoordinator =
    WorkspaceTransitionCoordinator(
        operations = recording,
        publication = recording,
        graphPublication = { WorkspaceGraphPublication.Ready },
        failureClassifier = WorkspaceTransitionFailureClassifier { failure ->
            WorkspaceTransitionFailureDisposition.Blocked(
                kind = TransitionBlockerKind.AdapterFailure,
                detail = failure.message?.takeIf(String::isNotBlank) ?: failure::class.qualifiedName.orEmpty(),
            )
        },
        initialPublished = recording.current(),
    )

private fun sourceRequest(): WorkspaceTransitionRequest.SourceFiles {
    val path = (WorkspaceSourcePath.parse("src/Sample.kt") as Refinement.Refined).value
    val hash = (WorkspaceSourceContentHash.parse("a".repeat(64)) as Refinement.Refined).value
    val claims = (
        WorkspaceSourceFreshnessClaims.refine(
            listOf(
                WorkspaceSourceFreshnessClaim(
                    path,
                    WorkspaceSourceContentIdentity.Present(hash),
                ),
            ),
        ) as Refinement.Refined
                 ).value
    return WorkspaceTransitionRequest.SourceFiles(claims)
}

private fun PublishedWorkspaceGenerationState.publication(): PublishedWorkspaceGeneration =
    (this as PublishedWorkspaceGenerationState.Published).publication

private fun identity(value: String): WorkspaceStateIdentity =
    (WorkspaceStateIdentity.parse(value) as Refinement.Refined).value

private fun generation(value: Long): EvidenceGeneration =
    (EvidenceGeneration.parse(value) as Refinement.Refined).value

private fun publication(
    generation: Long,
    identity: String,
): PublishedWorkspaceGeneration =
    PublishedWorkspaceGeneration(generation(generation), identity(identity))
