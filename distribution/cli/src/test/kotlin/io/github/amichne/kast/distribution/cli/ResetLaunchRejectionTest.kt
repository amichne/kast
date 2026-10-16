package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.installationResetFence
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ResetLaunchRejectionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `successful bootout with a live job exhausts the finite observation budget`() {
        val root = root()
        val runtime =
            ResetRuntimeScript(
                root,
                listOf(
                    ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(0)),
                    ResetScriptStep.Job(true, false, ResetCommandObservation.Exited(0)),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(0)),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(0)),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(0)),
                ),
            )
        val times = ArrayDeque(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, java.time.Duration.ofSeconds(30).toNanos()))
        val pauses = ArrayDeque(List(3) { java.time.Duration.ofMillis(100) })
        val timing =
            ResetRetirementTiming(
                clock = {
                    assertTrue(times.isNotEmpty(), "unexpected clock observation")
                    times.removeFirst()
                },
                pause = {
                    assertTrue(pauses.isNotEmpty(), "unexpected retirement pause")
                    assertEquals(pauses.removeFirst(), it)
                    ResetRetirementPause.CONTINUE
                },
            )
        val observed = mutableListOf<ResetLaunchObservation>()
        assertEquals(
            ResetEffect.Rejected(ForceResetFailure.LAUNCH_JOB_REJECTED),
            ForceDaemonRetirement(runtime, timing, observed::add).retire(root, home()),
        )
        assertTrue(times.isEmpty(), "unconsumed clock observations")
        assertTrue(pauses.isEmpty(), "unconsumed retirement pauses")
        assertEquals(
            listOf(
                ResetLaunchObservation(
                    ResetLaunchStage.WAIT,
                    ResetLaunchVariant.LEGACY_LOGIN,
                    ResetCommandObservation.DeadlineExceeded,
                )
            ),
            observed,
        )
        runtime.assertConsumed()
    }

    @Test
    fun `launch job that survives bootout prevents deletion and preserves prior payload`() {
        val home = home()
        val root = root()
        Files.createDirectories(root)
        Files.writeString(root.resolve("sentinel"), "prior payload")
        val runtime =
            ResetRuntimeScript(
                root,
                listOf(
                    ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(0)),
                    ResetScriptStep.Job(true, false, ResetCommandObservation.Exited(5)),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(0)),
                ),
            )
        val observations = mutableListOf<ResetObservation>()
        val result =
            forceResetInstallation(
                root,
                home,
                emptyMap(),
                ForceResetOperation.UNINSTALL,
                ForceResetExecution(runtime, forbiddenInstaller(), observations::add),
            )
                as ForceResetOutcome.Retained
        runtime.assertConsumed()
        assertEquals(ForceResetOperation.UNINSTALL, result.operation)
        assertEquals(ForceResetStage.DAEMONS, result.stage)
        assertEquals(ForceResetFailure.LAUNCH_JOB_REJECTED, result.failure)
        assertEquals("prior payload", Files.readString(Path.of(result.recoveryPath).resolve("payload/sentinel")))
        assertFalse(Files.exists(root))
        assertTrue(Files.exists(installationResetFence(root)))
        assertEquals(refusedDaemonObservations(), observations)
    }

    @Test
    fun `interrupted job retirement preserves the typed wait cause`() {
        val root = root()
        val runtime =
            ResetRuntimeScript(
                root,
                listOf(
                    ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(0)),
                    ResetScriptStep.Job(true, false, ResetCommandObservation.Exited(0)),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(0)),
                ),
            )
        val observed = mutableListOf<ResetLaunchObservation>()
        val timing =
            ResetRetirementTiming(
                clock = { 0 },
                pause = {
                    assertEquals(java.time.Duration.ofMillis(100), it)
                    ResetRetirementPause.INTERRUPTED
                },
            )
        assertEquals(
            ResetEffect.Rejected(ForceResetFailure.LAUNCH_JOB_REJECTED),
            ForceDaemonRetirement(runtime, timing, observed::add).retire(root, home()),
        )
        assertEquals(
            listOf(
                ResetLaunchObservation(
                    ResetLaunchStage.WAIT,
                    ResetLaunchVariant.LEGACY_LOGIN,
                    ResetCommandObservation.Interrupted,
                )
            ),
            observed,
        )
        runtime.assertConsumed()
    }

    private fun refusedDaemonObservations(): List<ResetObservation> =
        listOf(
            ResetObservation.Started(ForceResetOperation.UNINSTALL, ForceResetStage.FENCE),
            ResetObservation.Completed(ForceResetOperation.UNINSTALL, ForceResetStage.FENCE),
            ResetObservation.Started(ForceResetOperation.UNINSTALL, ForceResetStage.DAEMONS),
            ResetObservation.Rejected(
                ForceResetOperation.UNINSTALL,
                ForceResetStage.DAEMONS,
                ForceResetFailure.LAUNCH_JOB_REJECTED,
            ),
        )

    private fun forbiddenInstaller(): ForceReinstaller = ForceReinstaller { _, _, _ ->
        error("installer cannot run before quiescence")
    }

    private fun home(): Path = Files.createDirectories(temporary.resolve("home"))

    private fun root(): Path = temporary.toRealPath().resolve("kast")
}
