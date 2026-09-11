package io.github.amichne.kast.fixtureprobe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ProbeSetupObservationTest {
    private val generation = ProbeSetupGeneration(roots = 1, workspace = 1, vfs = 2, psi = 3, dumb = 4)
    private val completed =
        ProbeSetupSample(ProbeSetupStatus.CANDIDATE, generation, ProbeImportState.FINAL_TASKS_FINISHED)

    @Test
    fun unchangedReadySampleRetainsObservedImport() {
        val result =
            assertInstanceOf(
                ProbeResult.Accepted::class.java,
                ProbeSetupObservation.admit(completed, completed, SETUP_QUIET_WINDOW_NANOS),
            )
        assertEquals(ProbeSetupImportEvidence.FINAL_TASKS_OBSERVED, (result.value as ProbeSetupObservation).import)
        val restored = completed.copy(import = ProbeImportState.NOT_OBSERVED)
        val reopened =
            assertInstanceOf(
                ProbeResult.Accepted::class.java,
                ProbeSetupObservation.admit(restored, restored, SETUP_QUIET_WINDOW_NANOS),
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
                generation.copy(roots = 2),
                generation.copy(workspace = 2),
                generation.copy(vfs = 3),
                generation.copy(psi = 4),
                generation.copy(dumb = 5),
            )) {
            assertInstanceOf(
                ProbeResult.Rejected::class.java,
                ProbeSetupObservation.admit(completed, completed.copy(generation = changed), SETUP_QUIET_WINDOW_NANOS),
            )
        }
        assertInstanceOf(
            ProbeResult.Rejected::class.java,
            ProbeSetupObservation.admit(completed, completed, SETUP_QUIET_WINDOW_NANOS - 1),
        )
    }

    @Test
    fun unreadyAndIncompleteImportRemainFailures() {
        for (status in ProbeSetupStatus.entries.filter { it != ProbeSetupStatus.CANDIDATE }) {
            val busy = completed.copy(status = status)
            assertInstanceOf(
                ProbeResult.Rejected::class.java,
                ProbeSetupObservation.admit(busy, busy, SETUP_QUIET_WINDOW_NANOS),
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
                ProbeSetupObservation.admit(incomplete, incomplete, SETUP_QUIET_WINDOW_NANOS),
            )
        }
    }
}
