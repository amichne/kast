package io.github.amichne.kast.runtime.ide.read

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SingleFlightTest {
    @Test
    fun `generated report binds exact success evidence`() {
        assertExactSingleFlightReport()
    }

    @Test
    fun `release and cancellation return one active permit to idle`() {
        val fixture = FreshnessFixture()
        val controller = controller(fixture.capability())
        val released = active(controller.admit(fixture.capability()))
        assertEquals(
            ProjectReadPermitEnd.Ended(
                ProjectReadPermitTerminal.Released,
                ProjectReadContinuation.Idle,
            ),
            controller.release(released),
        )

        fixture.advance()
        val cancelled = active(controller.admit(fixture.capability()))
        assertEquals(
            ProjectReadPermitEnd.Ended(
                ProjectReadPermitTerminal.Cancelled(
                    ProjectReadCancellationCause.CLIENT_DISCONNECTED,
                ),
                ProjectReadContinuation.Idle,
            ),
            controller.cancel(cancelled, ProjectReadCancellationCause.CLIENT_DISCONNECTED),
        )
        assertTrue(controller.admit(fixture.capability()) is ProjectReadAdmission.Active)
    }

    @Test
    fun `release promotes the exact queued freshness once`() {
        val fixture = FreshnessFixture()
        val controller = controller(fixture.capability())
        val first = active(controller.admit(fixture.capability()))
        fixture.advance()
        val queued = queued(controller.admit(fixture.capability()))

        val ended = controller.release(first) as ProjectReadPermitEnd.Ended
        val promotion = ended.continuation as ProjectReadContinuation.Promoted
        assertSame(queued, promotion.request)
        assertEquals(ProjectReadPermitTerminal.Released, ended.terminal)
        assertTrue(controller.admit(fixture.capability()) is ProjectReadAdmission.Queued)
    }

    @Test
    fun `active cancellation promotes while queued cancellation frees the slot`() {
        val fixture = FreshnessFixture()
        val controller = controller(fixture.capability())
        val active = active(controller.admit(fixture.capability()))
        val queued = queued(controller.admit(fixture.capability()))
        assertEquals(
            QueuedProjectReadCancellation.Cancelled(
                ProjectReadCancellationCause.REQUEST_CANCELLED,
            ),
            controller.cancelQueued(queued, ProjectReadCancellationCause.REQUEST_CANCELLED),
        )
        val replacement = queued(controller.admit(fixture.capability()))
        val ended = controller.cancel(
            active,
            ProjectReadCancellationCause.CLIENT_DISCONNECTED,
        ) as ProjectReadPermitEnd.Ended
        val promotion = ended.continuation as ProjectReadContinuation.Promoted
        assertSame(replacement, promotion.request)
        assertEquals(
            ProjectReadPermitTerminal.Cancelled(
                ProjectReadCancellationCause.CLIENT_DISCONNECTED,
            ),
            ended.terminal,
        )
    }

    @Test
    fun `every live state retires all authority exactly once`() {
        ProjectReadRetirementCause.entries.forEach { cause ->
            val idleFixture = FreshnessFixture("/tmp/kast-single-flight-idle-${cause.name}")
            val idle = controller(idleFixture.capability())
            assertEquals(
                ProjectReadRetirement.Retired(cause, RetiredProjectReadAuthority.None),
                idle.retire(cause),
            )

            val activeFixture = FreshnessFixture("/tmp/kast-single-flight-active-${cause.name}")
            val activeController = controller(activeFixture.capability())
            val activePermit = active(activeController.admit(activeFixture.capability()))
            val activeRetirement = activeController.retire(cause) as ProjectReadRetirement.Retired
            assertSame(
                activePermit,
                (activeRetirement.authority as RetiredProjectReadAuthority.Active).permit,
            )
            assertEquals(
                ProjectReadPermitEnd.AlreadyEnded(ProjectReadPermitTerminal.Retired(cause)),
                activeController.release(activePermit),
            )

            val fullFixture = FreshnessFixture("/tmp/kast-single-flight-full-${cause.name}")
            val full = controller(fullFixture.capability())
            val fullPermit = active(full.admit(fullFixture.capability()))
            val fullQueue = queued(full.admit(fullFixture.capability()))
            val fullRetirement = full.retire(cause) as ProjectReadRetirement.Retired
            val authority = fullRetirement.authority as
                RetiredProjectReadAuthority.ActiveAndQueued
            assertSame(fullPermit, authority.permit)
            assertSame(fullQueue, authority.request)
            assertEquals(
                QueuedProjectReadCancellation.AlreadyTerminal(
                    QueuedProjectReadTerminal.Retired(cause),
                ),
                full.cancelQueued(fullQueue, ProjectReadCancellationCause.REQUEST_CANCELLED),
            )
            assertEquals(
                ProjectReadRetirement.AlreadyRetired(cause),
                full.retire(nextCause(cause)),
            )
            assertEquals(
                ProjectReadAdmission.Rejected(ProjectReadAdmissionFailure.Retired(cause)),
                full.admit(fullFixture.capability()),
            )
        }
    }

    @Test
    fun `independent controllers do not share admission state`() {
        val firstFixture = FreshnessFixture("/tmp/kast-single-flight-first")
        val secondFixture = FreshnessFixture("/tmp/kast-single-flight-second")
        val first = controller(firstFixture.capability())
        val second = controller(secondFixture.capability())

        assertTrue(first.admit(firstFixture.capability()) is ProjectReadAdmission.Active)
        assertTrue(second.admit(secondFixture.capability()) is ProjectReadAdmission.Active)
        assertTrue(first.admit(firstFixture.capability()) is ProjectReadAdmission.Queued)
        assertTrue(second.admit(secondFixture.capability()) is ProjectReadAdmission.Queued)
    }

    private fun nextCause(
        cause: ProjectReadRetirementCause,
    ): ProjectReadRetirementCause = ProjectReadRetirementCause.entries[
        (cause.ordinal + 1) % ProjectReadRetirementCause.entries.size
    ]
}
