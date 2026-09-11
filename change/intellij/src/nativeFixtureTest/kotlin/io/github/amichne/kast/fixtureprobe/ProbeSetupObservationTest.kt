package io.github.amichne.kast.fixtureprobe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ProbeSetupObservationTest {
    private val generation = ProbeSetupGeneration(imports = 1, roots = 1, workspace = 1, vfs = 2, psi = 3, dumb = 4)
    private val completed =
        ProbeSetupSample(
            status = ProbeSetupStatus.CANDIDATE,
            generation = generation,
            import = ProbeImportState.FINAL_TASKS_FINISHED,
            provenance = ProbeSourceProvenance.AUTHORED,
            indexing = ProbeSetupIndexingState.IDLE,
            refresh = ProbeSetupRefreshState(ProbeSetupQueueState.IDLE, ProbeSetupQueueState.IDLE),
        )

    @Test
    fun smartModeWithScheduledWorkDoesNotProveReadiness() {
        assertEquals(
            ProbeSetupIndexingState.SCHEDULED,
            ProbeSetupIndexingState.observe(isDumb = false, hasScheduledTasks = true),
        )
        val scheduled = completed.copy(indexing = ProbeSetupIndexingState.SCHEDULED)
        assertInstanceOf(
            ProbeResult.Rejected::class.java,
            ProbeSetupObservation.admit(
                before = scheduled,
                after = scheduled,
                elapsedNanos = SETUP_QUIET_WINDOW_NANOS,
                drain = ProbeSetupDrainState.COMPLETED,
            ),
        )
        val unavailable = completed.copy(indexing = ProbeSetupIndexingState.UNAVAILABLE)
        assertInstanceOf(
            ProbeResult.Rejected::class.java,
            ProbeSetupObservation.admit(
                before = unavailable,
                after = unavailable,
                elapsedNanos = SETUP_QUIET_WINDOW_NANOS,
                drain = ProbeSetupDrainState.COMPLETED,
            ),
        )
    }

    @Test
    fun everyActiveOrUnavailableGlobalQueueRejectsReadiness() {
        for (scanning in ProbeSetupQueueState.entries) {
            for (processing in ProbeSetupQueueState.entries) {
                if (scanning == ProbeSetupQueueState.IDLE && processing == ProbeSetupQueueState.IDLE) continue
                val busy = completed.copy(refresh = ProbeSetupRefreshState(scanning, processing))
                assertEquals(
                    ProbeResult.Rejected(ProbeFailure.SETUP_MOVING),
                    ProbeSetupObservation.admit(
                        before = busy,
                        after = busy,
                        elapsedNanos = SETUP_QUIET_WINDOW_NANOS,
                        drain = ProbeSetupDrainState.COMPLETED,
                    ),
                )
            }
        }
    }

    @Test
    fun unchangedReadySampleRetainsObservedImport() {
        val result =
            assertInstanceOf(
                ProbeResult.Accepted::class.java,
                ProbeSetupObservation.admit(
                    before = completed,
                    after = completed,
                    elapsedNanos = SETUP_QUIET_WINDOW_NANOS,
                    drain = ProbeSetupDrainState.COMPLETED,
                ),
            )
        val observation = result.value as ProbeSetupObservation
        assertEquals(ProbeSetupImportEvidence.FINAL_TASKS_OBSERVED, observation.import)
        assertEquals(completed, observation.before)
        assertEquals(completed, observation.after)
        assertEquals(ProbeSetupDrainState.COMPLETED, observation.drain)
        val restored = completed.copy(import = ProbeImportState.NOT_OBSERVED)
        val reopened =
            assertInstanceOf(
                ProbeResult.Accepted::class.java,
                ProbeSetupObservation.admit(
                    before = restored,
                    after = restored,
                    elapsedNanos = SETUP_QUIET_WINDOW_NANOS,
                    drain = ProbeSetupDrainState.COMPLETED,
                ),
            )
        assertEquals(
            ProbeSetupImportEvidence.NOT_OBSERVED_PERSISTED_MODEL,
            (reopened.value as ProbeSetupObservation).import,
        )
    }

    @Test
    fun everyModelOrIndexMovementInvalidatesQuietObservation() {
        for (changed in
            listOf(
                generation.copy(imports = 2),
                generation.copy(roots = 2),
                generation.copy(workspace = 2),
                generation.copy(vfs = 3),
                generation.copy(psi = 4),
                generation.copy(dumb = 5),
            )) {
            assertInstanceOf(
                ProbeResult.Rejected::class.java,
                ProbeSetupObservation.admit(
                    before = completed,
                    after = completed.copy(generation = changed),
                    elapsedNanos = SETUP_QUIET_WINDOW_NANOS,
                    drain = ProbeSetupDrainState.COMPLETED,
                ),
            )
        }
        assertInstanceOf(
            ProbeResult.Rejected::class.java,
            ProbeSetupObservation.admit(
                before = completed,
                after = completed,
                elapsedNanos = SETUP_QUIET_WINDOW_NANOS - 1,
                drain = ProbeSetupDrainState.COMPLETED,
            ),
        )
        val negative = completed.copy(generation = generation.copy(vfs = -1))
        assertEquals(
            ProbeResult.Rejected(ProbeFailure.SETUP_MOVING),
            ProbeSetupObservation.admit(
                before = negative,
                after = negative,
                elapsedNanos = SETUP_QUIET_WINDOW_NANOS,
                drain = ProbeSetupDrainState.COMPLETED,
            ),
        )
    }

    @Test
    fun unreadyAndIncompleteImportRemainFailures() {
        for (status in ProbeSetupStatus.entries.filter { it != ProbeSetupStatus.CANDIDATE }) {
            val busy = completed.copy(status = status)
            assertInstanceOf(
                ProbeResult.Rejected::class.java,
                ProbeSetupObservation.admit(
                    before = busy,
                    after = busy,
                    elapsedNanos = SETUP_QUIET_WINDOW_NANOS,
                    drain = ProbeSetupDrainState.COMPLETED,
                ),
            )
        }
        for (state in
            listOf(
                ProbeImportState.IMPORTING,
                ProbeImportState.IMPORT_FINISHED,
                ProbeImportState.FINALIZING,
                ProbeImportState.FAILED,
            )) {
            val incomplete = completed.copy(import = state)
            assertInstanceOf(
                ProbeResult.Rejected::class.java,
                ProbeSetupObservation.admit(
                    before = incomplete,
                    after = incomplete,
                    elapsedNanos = SETUP_QUIET_WINDOW_NANOS,
                    drain = ProbeSetupDrainState.COMPLETED,
                ),
            )
        }
    }
}
