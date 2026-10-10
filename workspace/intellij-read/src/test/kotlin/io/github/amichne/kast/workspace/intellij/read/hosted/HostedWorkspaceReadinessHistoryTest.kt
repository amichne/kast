package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessDetail
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessFixture
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HostedWorkspaceReadinessHistoryTest {
    private val root =
        (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/detached-project")) as Refinement.Refined).value

    @Test
    fun `M0 remains informational through rejected refresh and is replaced only by observed M1`() {
        val fixture = WorkspaceReadinessFixture(root)
        val history = HostedWorkspaceReadinessHistory()
        val first = fixture.ready()
        assertSame(first, history.record(first))
        val rejected = unavailable(fixture)
        val current = assertInstanceOf(WorkspaceCapabilityReadiness.Unavailable::class.java, history.record(rejected))
        assertEquals(rejected.reason, current.reason)
        assertEquals(rejected.nextAction, current.nextAction)
        val evidence = assertInstanceOf(WorkspaceReadinessDetail.PreviouslyObservedModel::class.java, current.detail)
        assertSame(first, evidence.observation)
        assertSame(rejected.detail, evidence.currentDetail)
        fixture.advance()
        val second = fixture.ready()
        assertSame(second, history.record(second))
        val subsequent = history.record(unavailable(fixture)) as WorkspaceCapabilityReadiness.Unavailable
        assertSame(second, (subsequent.detail as WorkspaceReadinessDetail.PreviouslyObservedModel).observation)
    }

    @Test
    fun `disposal and another incarnation cannot receive current authority from retained M0`() {
        val fixture = WorkspaceReadinessFixture(root, UUID(0, 1))
        val foreign = WorkspaceReadinessFixture(root, UUID(0, 2))
        val history = HostedWorkspaceReadinessHistory()
        val first = fixture.ready()
        history.record(first)
        val different = unavailable(foreign)
        assertSame(different, history.record(different))
        val disposed =
            WorkspaceCapabilityReadiness.Blocked(
                fixture.identity,
                WorkspaceReadinessReason.PROJECT_DISPOSED,
                WorkspaceReadinessNextAction.REOPEN_PROJECT,
            )
        val current = assertInstanceOf(WorkspaceCapabilityReadiness.Blocked::class.java, history.record(disposed))
        assertEquals(WorkspaceReadinessReason.PROJECT_DISPOSED, current.reason)
        assertEquals(WorkspaceReadinessNextAction.REOPEN_PROJECT, current.nextAction)
        assertSame(first, (current.detail as WorkspaceReadinessDetail.PreviouslyObservedModel).observation)
    }

    @Test
    fun `bounded observation sequences preserve current classification and latest matching native model`() {
        val events = Event.entries
        fun verify(sequence: List<Event>) {
            val fixture = WorkspaceReadinessFixture(root, UUID(0, 1))
            val foreign = WorkspaceReadinessFixture(root, UUID(0, 2))
            val history = HostedWorkspaceReadinessHistory()
            var previous: WorkspaceCapabilityReadiness.Ready? = null
            sequence.forEach { event ->
                val observed = observeEvent(event, fixture, foreign)
                previous = assertCurrentObservation(observed, history.record(observed), previous)
            }
        }
        fun enumerate(prefix: List<Event>, remaining: Int) {
            verify(prefix)
            if (remaining > 0) events.forEach { enumerate(prefix + it, remaining - 1) }
        }
        enumerate(emptyList(), 4)
    }

    @Test
    fun `regression oracle rejects dropping M0 or promoting M0 into current ready`() {
        val fixture = WorkspaceReadinessFixture(root)
        val first = fixture.ready()
        val rejected = unavailable(fixture)
        fun requireRetainedRejection(current: WorkspaceCapabilityReadiness) {
            val unavailable = assertInstanceOf(WorkspaceCapabilityReadiness.Unavailable::class.java, current)
            val evidence =
                assertInstanceOf(WorkspaceReadinessDetail.PreviouslyObservedModel::class.java, unavailable.detail)
            assertSame(first, evidence.observation)
            assertSame(rejected.detail, evidence.currentDetail)
        }
        val production = HostedWorkspaceReadinessHistory()
        production.record(first)
        requireRetainedRejection(production.record(rejected))
        assertThrows(AssertionError::class.java) { requireRetainedRejection(rejected) }
        assertThrows(AssertionError::class.java) { requireRetainedRejection(first) }
    }

    /** Expected identity and retained model come from the input sequence, never production history. */
    private fun assertCurrentObservation(
        observed: WorkspaceCapabilityReadiness,
        current: WorkspaceCapabilityReadiness,
        previous: WorkspaceCapabilityReadiness.Ready?,
    ): WorkspaceCapabilityReadiness.Ready? {
        assertEquals(observed::class, current::class)
        assertEquals(observed.identity, current.identity)
        if (observed is WorkspaceCapabilityReadiness.Ready) {
            assertSame(observed, current)
            return observed
        }
        if (previous == null || previous.identity != observed.identity) {
            assertSame(observed, current)
            return previous
        }
        val detail =
            when (current) {
                is WorkspaceCapabilityReadiness.Unavailable -> current.detail
                is WorkspaceCapabilityReadiness.Pending -> current.detail
                else -> error("Unexpected current observation")
            }
                as WorkspaceReadinessDetail.PreviouslyObservedModel
        assertSame(previous, detail.observation)
        return previous
    }

    private fun observeEvent(
        event: Event,
        fixture: WorkspaceReadinessFixture,
        foreign: WorkspaceReadinessFixture,
    ): WorkspaceCapabilityReadiness =
        when (event) {
            Event.MODEL -> {
                fixture.advance()
                fixture.ready()
            }
            Event.UNAVAILABLE -> unavailable(fixture)
            Event.CANCELLED ->
                WorkspaceCapabilityReadiness.Unavailable(
                    fixture.identity,
                    WorkspaceReadinessReason.OBSERVATION_CANCELLED,
                    WorkspaceReadinessNextAction.OBSERVE_AGAIN,
                )
            Event.INDEXING ->
                WorkspaceCapabilityReadiness.Pending(
                    fixture.identity,
                    WorkspaceReadinessReason.INDEXING,
                    WorkspaceReadinessNextAction.OBSERVE_AGAIN,
                )
            Event.FOREIGN -> unavailable(foreign)
        }

    private fun unavailable(fixture: WorkspaceReadinessFixture) =
        WorkspaceCapabilityReadiness.Unavailable(
            fixture.identity,
            WorkspaceReadinessReason.EPOCH_UNAVAILABLE,
            WorkspaceReadinessNextAction.OBSERVE_AGAIN,
            WorkspaceReadinessDetail.EpochRejected(ProjectReadEpochObservationFailure.GradleModelUnavailable),
        )

    private enum class Event {
        MODEL,
        UNAVAILABLE,
        CANCELLED,
        INDEXING,
        FOREIGN,
    }
}
