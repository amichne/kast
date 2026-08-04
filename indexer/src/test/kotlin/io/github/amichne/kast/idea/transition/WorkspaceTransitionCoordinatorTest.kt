package io.github.amichne.kast.idea.transition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

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
        assertEquals(SemanticGeneration(1), coordinator.snapshot().published?.generation)
    }

    @Test
    fun `event during reconciliation discards candidate and schedules another cycle`() {
        lateinit var coordinator: WorkspaceTransitionCoordinator
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
    fun `build refresh failure blocks and preserves published generation`() {
        val previous = PublishedWorkspaceGeneration(
            generation = SemanticGeneration(7),
            identity = WorkspaceStateIdentity("previous"),
        )
        val operations = RecordingOperations(refreshFailure = IllegalStateException("Gradle model unavailable"))
        val coordinator = WorkspaceTransitionCoordinator(operations, previous)
        coordinator.observe(WorkspaceSignal.BuildSemantic)

        assertEquals(TransitionRun.Blocked, coordinator.reconcilePending())
        val snapshot = coordinator.snapshot()
        assertEquals(WorkspaceLifecycle.Blocked, snapshot.lifecycle)
        assertEquals(previous, snapshot.published)
        assertEquals("Gradle model unavailable", snapshot.blocker?.detail)
    }
}

private class RecordingOperations(
    private val identities: ArrayDeque<WorkspaceStateIdentity> = ArrayDeque(),
    private val reconcile: () -> Unit = {},
    private val refreshFailure: Throwable? = null,
) : WorkspaceTransitionOperations {
    val reconciliations = AtomicInteger()
    val published = mutableListOf<WorkspaceStateIdentity>()

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

    override fun publish(generation: PublishedWorkspaceGeneration): GenerationPublication {
        published += generation.identity
        return GenerationPublication.Published
    }
}
