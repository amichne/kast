package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class WorkspaceEpochValidationTest {
    @Test
    fun `stale final observation and reopened incarnation cannot publish retained evidence`() {
        var signal = 0
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(signal) }
        val admitted = source.epoch()
        signal++
        assertSame(WorkspaceEpochValidation.Stale, validateWorkspaceEpoch(admitted, source.observe()))
        val reopened = ProjectReadEpoch.Source.create { Refinement.Refined(signal) }
        assertSame(WorkspaceEpochValidation.DifferentIncarnation, validateWorkspaceEpoch(admitted, reopened.observe()))
        val disposed = ProjectReadEpochObservation.Rejected(ProjectReadEpochObservationFailure.ProjectDisposed)
        assertEquals(WorkspaceEpochValidation.Unavailable(disposed.failure), validateWorkspaceEpoch(admitted, disposed))
    }

    @Test
    fun `equal current native facts preserve admission across irrelevant metadata`() {
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(7) }
        val admitted = source.epoch()
        // The metadata cannot enter the production rule. Actual content/model movement is a different input.
        val metadata =
            listOf(
                "checkout-a",
                "checkout-b",
                "branch-a",
                "branch-b",
                "registered",
                "unregistered",
                "installed",
                "absent",
            )
        metadata.forEach {
            val current =
                assertInstanceOf(
                    WorkspaceEpochValidation.Current::class.java,
                    validateWorkspaceEpoch(admitted, source.observe()),
                )
            assertEquals(ProjectReadEpochRelation.SAME, admitted.relationTo(current.epoch))
        }
    }

    @Test
    fun `bounded generated prefixes require same incarnation unchanged state and nonretired observation`() {
        val events = Event.entries
        fun visit(prefix: List<Event>, remaining: Int) {
            checkSequence(prefix, ::validateWorkspaceEpoch)
            if (remaining > 0) events.forEach { visit(prefix + it, remaining - 1) }
        }
        visit(emptyList(), 4)
    }

    @Test
    fun `generated oracle rejects stale snapshot and retired source mutations`() {
        assertFalse(
            sequenceAccepted(listOf(Event.MOVE)) { admitted, _ ->
                validateWorkspaceEpoch(admitted, ProjectReadEpochObservation.Observed(admitted))
            }
        )
        assertFalse(
            sequenceAccepted(listOf(Event.DISPOSE)) { admitted, _ -> WorkspaceEpochValidation.Current(admitted) }
        )
        assertFalse(
            sequenceAccepted(listOf(Event.REOPEN)) { admitted, _ -> WorkspaceEpochValidation.Current(admitted) }
        )
    }

    private fun checkSequence(
        events: List<Event>,
        validate: (ProjectReadEpoch<*>, ProjectReadEpochObservation) -> WorkspaceEpochValidation,
    ) = assertEquals(true, sequenceAccepted(events, validate), events.toString())

    /** Oracle tracks independent fixture state, never asks the product epoch comparison for expected admission. */
    private fun sequenceAccepted(
        events: List<Event>,
        validate: (ProjectReadEpoch<*>, ProjectReadEpochObservation) -> WorkspaceEpochValidation,
    ): Boolean {
        var revision = 0
        var incarnation = 0
        var retired = false
        fun source() =
            ProjectReadEpoch.Source.create {
                if (retired) Refinement.Rejected(ProjectReadEpochObservationFailure.ProjectDisposed)
                else Refinement.Refined(revision)
            }
        var currentSource = source()
        var admitted = currentSource.epoch()
        var admittedRevision = revision
        var admittedIncarnation = incarnation
        for (event in listOf<Event?>(null) + events) {
            when (event) {
                Event.MOVE -> revision++
                Event.DISPOSE -> retired = true
                Event.REOPEN -> {
                    retired = false
                    incarnation++
                    currentSource = source()
                }
                Event.ADMIT ->
                    if (!retired) {
                        admitted = currentSource.epoch()
                        admittedRevision = revision
                        admittedIncarnation = incarnation
                    }
                Event.METADATA,
                null -> Unit
            }
            val actual = validate(admitted, currentSource.observe())
            if (
                !matchesOracle(
                    actual,
                    OracleObservation(revision, incarnation, retired),
                    OracleAdmission(admittedRevision, admittedIncarnation),
                )
            )
                return false
        }
        return true
    }

    /** Expectations use only independent fixture revisions and incarnation/disposal events. */
    private fun matchesOracle(
        actual: WorkspaceEpochValidation,
        observed: OracleObservation,
        admitted: OracleAdmission,
    ): Boolean =
        when {
            observed.retired ->
                actual == WorkspaceEpochValidation.Unavailable(ProjectReadEpochObservationFailure.ProjectDisposed)
            observed.incarnation != admitted.incarnation -> actual == WorkspaceEpochValidation.DifferentIncarnation
            observed.revision != admitted.revision -> actual == WorkspaceEpochValidation.Stale
            else -> actual is WorkspaceEpochValidation.Current
        }

    private data class OracleObservation(val revision: Int, val incarnation: Int, val retired: Boolean)

    private data class OracleAdmission(val revision: Int, val incarnation: Int)

    private enum class Event {
        MOVE,
        DISPOSE,
        REOPEN,
        ADMIT,
        METADATA,
    }
}

private fun <State : Any> ProjectReadEpoch.Source<State>.epoch(): ProjectReadEpoch<*> =
    (observe() as ProjectReadEpochObservation.Observed).epoch
