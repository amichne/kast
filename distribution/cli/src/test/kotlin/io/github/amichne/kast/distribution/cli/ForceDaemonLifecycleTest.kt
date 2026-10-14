package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.installationResetFence
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ForceDaemonLifecycleTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `process selection admits exact Kast identity while excluding shared and foreign processes`() {
        val root = Path.of("/fixture/kast")
        val daemon = snapshot(root)
        val selected = ResetScopedProcess.select(daemon, root) as ResetProcessSelection.Selected
        assertEquals(daemon, selected.process.snapshot)
        val excluded =
            listOf(
                daemon.copy(arguments = listOf("-cp", "/fixture/kast-other/lib/kast.jar", DAEMON_MAIN)),
                daemon.copy(
                    arguments = listOf("-cp", "/fixture/kast/lib/kast.jar:/foreign/dependency.jar", DAEMON_MAIN)
                ),
                daemon.copy(arguments = listOf("-cp", "relative/kast.jar", DAEMON_MAIN)),
                daemon.copy(
                    arguments =
                        listOf("-cp", "/fixture/kast/lib/kast.jar", "org.gradle.launcher.daemon.bootstrap.GradleDaemon")
                ),
                daemon.copy(command = "/Applications/IDEA.app/Contents/MacOS/idea", arguments = emptyList()),
                daemon.copy(command = "/fixture/kast-other/installation/bin/kast-daemon", arguments = emptyList()),
                daemon.copy(command = "/fixture/kast/../foreign/kast-daemon", arguments = emptyList()),
            )
        excluded.forEach { assertEquals(ResetProcessSelection.Unrelated, ResetScopedProcess.select(it, root)) }
        assertEquals(ResetProcessSelection.Rejected, ResetScopedProcess.select(daemon.copy(startEpochMillis = 0), root))
        val native = daemon.copy(command = "/fixture/kast/installation/bin/kast-daemon", arguments = emptyList())
        assertTrue(ResetScopedProcess.select(native, root) is ResetProcessSelection.Selected)
    }

    @Test
    fun `reset selects an orphaned shared Codex listener by its exact installation transport`() {
        val root = Path.of("/fixture/kast")
        val listener =
            snapshot(root)
                .copy(
                    command = "/fixture/bin/codex",
                    arguments =
                        listOf(
                            "-c",
                            "features.code_mode_host=true",
                            "app-server",
                            "--analytics-default-enabled",
                            "--listen",
                            "unix:///fixture/kast/installation/state/run/u.sock",
                        ),
                )
        val selected = ResetScopedProcess.select(listener, root) as ResetProcessSelection.Selected
        assertEquals(listener, selected.process.snapshot)
        val excluded =
            listOf(
                listener.copy(
                    arguments =
                        listener.arguments.dropLast(1) + "unix:///fixture/kast-other/installation/state/run/u.sock"
                ),
                listener.copy(arguments = listener.arguments.dropLast(1) + "unix:///fixture/kast/../foreign/u.sock"),
                listener.copy(
                    arguments = listener.arguments.dropLast(1) + "unix:///fixture/kast/installation/state/run/c.sock"
                ),
                listener.copy(arguments = listOf("app-server", "daemon", "pid-update-loop")),
                listener.copy(arguments = listOf("-c", "app-server", "exec", "--listen", listener.arguments.last())),
                listener.copy(
                    arguments =
                        listOf("app-server", "--listen", listener.arguments.last(), "--listen", "unix:///foreign.sock")
                ),
                listener.copy(command = "/fixture/bin/unrelated"),
            )
        excluded.forEach { assertEquals(ResetProcessSelection.Unrelated, ResetScopedProcess.select(it, root)) }
        assertEquals(
            ResetProcessSelection.Rejected,
            ResetScopedProcess.select(listener.copy(startEpochMillis = 0), root),
        )
    }

    @Test
    fun `reset retires a Codex listener after its launch job and parent disappear`() {
        val root = root()
        val listener =
            snapshot(root)
                .copy(
                    command = "/fixture/bin/codex",
                    arguments =
                        listOf("app-server", "--listen", "unix://${root.resolve("installation/state/run/u.sock")}"),
                )
        val runtime =
            ResetRuntimeScript(
                root,
                listOf(
                    ResetScriptStep.Table(ResetProcessTable.Observed(listOf(listener))),
                    ResetScriptStep.Capture(listener, ResetScriptCaptureResult.NO_SHARED_DESCRIPTORS),
                ) +
                    absentJobs() +
                    listOf(
                        ResetScriptStep.Signal(listener, LifecycleProcessObservation.Absent),
                        ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
                    ),
            )
        assertEquals(ResetEffect.Completed, ForceDaemonRetirement(runtime).retire(root, home()))
        runtime.assertConsumed()
    }

    @Test
    fun `capture rejection prevents launch job retirement`() {
        val root = root()
        val prior = snapshot(root)
        val runtime =
            ResetRuntimeScript(
                root,
                listOf(
                    ResetScriptStep.Table(ResetProcessTable.Observed(listOf(prior))),
                    ResetScriptStep.Capture(prior, ResetScriptCaptureResult.REJECTED),
                ),
            )

        assertEquals(
            ResetEffect.Rejected(ForceResetFailure.PROCESS_RETIREMENT_REJECTED),
            ForceDaemonRetirement(runtime).retire(root, home()),
        )
        runtime.assertConsumed()
    }

    @Test
    fun `captured ownership survives launch job disappearance before process retirement`() {
        val root = root()
        val prior = snapshot(root)
        val runtime =
            ResetRuntimeScript(
                root,
                listOf(
                    ResetScriptStep.Table(ResetProcessTable.Observed(listOf(prior))),
                    ResetScriptStep.Capture(prior, ResetScriptCaptureResult.NO_SHARED_DESCRIPTORS),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(0)),
                    ResetScriptStep.Job(true, false, ResetCommandObservation.Exited(0)),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(113)),
                    ResetScriptStep.Job(false, true, ResetCommandObservation.Exited(113)),
                    ResetScriptStep.Signal(prior, LifecycleProcessObservation.Absent),
                    ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
                ),
            )

        assertEquals(ResetEffect.Completed, ForceDaemonRetirement(runtime).retire(root, home()))
        runtime.assertConsumed()
    }

    @Test
    fun `captured ownership survives delayed launch job disappearance`() {
        val root = root()
        val prior = snapshot(root)
        val runtime =
            ResetRuntimeScript(
                root,
                listOf(
                    ResetScriptStep.Table(ResetProcessTable.Observed(listOf(prior))),
                    ResetScriptStep.Capture(prior, ResetScriptCaptureResult.NO_SHARED_DESCRIPTORS),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(0)),
                    ResetScriptStep.Job(true, false, ResetCommandObservation.Exited(0)),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(0)),
                    ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(113)),
                    ResetScriptStep.Job(false, true, ResetCommandObservation.Exited(113)),
                    ResetScriptStep.Signal(prior, LifecycleProcessObservation.Absent),
                    ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
                ),
            )
        assertEquals(
            ResetEffect.Completed,
            ForceDaemonRetirement(
                    runtime,
                    ResetRetirementTiming(
                        clock = { 0 },
                        pause = {
                            assertEquals(java.time.Duration.ofMillis(100), it)
                            ResetRetirementPause.CONTINUE
                        },
                    ),
                )
                .retire(root, home()),
        )
        runtime.assertConsumed()
    }

    private fun home(): Path = Files.createDirectories(temporary.resolve("home"))

    private fun root(): Path = temporary.toRealPath().resolve("kast")

    private fun absentJobs(): List<ResetScriptStep> =
        listOf(
            ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(113)),
            ResetScriptStep.Job(false, true, ResetCommandObservation.Exited(113)),
        )

    private fun snapshot(root: Path): LifecycleProcessSnapshot =
        LifecycleProcessSnapshot(
            91,
            200,
            "/fixture/java",
            listOf("-cp", root.resolve("installation/lib/kast.jar").toString(), DAEMON_MAIN),
        )

    companion object {
        private const val DAEMON_MAIN = "io.github.amichne.kast.cli.KastDaemonMain"
        private const val GENERATION = "12345678-1234-1234-1234-123456789abc"
    }
}

class ForceDaemonActivationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `reinstallation activates only a verified cold payload and retains the observed generation`() {
        val root = root()
        val runtime =
            ResetRuntimeScript(
                root,
                closedRetirementSteps() +
                    listOf(
                        ResetScriptStep.Activate(LifecycleChildObservation.Exited(0)),
                        ResetScriptStep.Ready(ResetActivationObservation.Ready("1.2.3", GENERATION)),
                    ),
            )
        val observations = mutableListOf<ResetObservation>()
        val result =
            forceResetInstallation(
                root,
                home(),
                emptyMap(),
                ForceResetOperation.REINSTALL,
                ForceResetExecution(runtime, freshInstaller(), observations::add),
            )
        assertEquals(
            ForceResetOutcome.Reinstalled(
                root.resolve("installation").toString(),
                "1.2.3",
                root.resolve("bin/kast").toString(),
                GENERATION,
            ),
            result,
        )
        runtime.assertConsumed()
        assertFalse(Files.exists(installationResetFence(root)))
        assertEquals(successfulReplacementObservations(), observations)
    }

    @Test
    fun `failed bootstrap restores the fence and retires partial startup before returning pending`() {
        val root = root()
        val runtime =
            ResetRuntimeScript(
                root,
                closedRetirementSteps() +
                    ResetScriptStep.Activate(LifecycleChildObservation.Exited(5)) +
                    closedRetirementSteps(),
            )
        val observations = mutableListOf<ResetObservation>()
        val result =
            forceResetInstallation(
                root,
                home(),
                emptyMap(),
                ForceResetOperation.REINSTALL,
                ForceResetExecution(runtime, freshInstaller(), observations::add),
            )
        assertEquals(
            ForceResetOutcome.Pending(
                root.resolve("installation").toString(),
                "1.2.3",
                root.resolve("bin/kast").toString(),
                ForceResetFailure.ACTIVATION_REJECTED,
            ),
            result,
        )
        runtime.assertConsumed()
        assertTrue(Files.exists(installationResetFence(root)))
        assertEquals(
            listOf(
                ResetObservation.Rejected(
                    ForceResetOperation.REINSTALL,
                    ForceResetStage.ACTIVATION,
                    ForceResetFailure.ACTIVATION_REJECTED,
                ),
                ResetObservation.Started(ForceResetOperation.REINSTALL, ForceResetStage.RECOVERY),
                ResetObservation.Completed(ForceResetOperation.REINSTALL, ForceResetStage.RECOVERY),
            ),
            observations.takeLast(3),
        )
    }

    @Test
    fun `failed recovery preserves both activation and retirement failures`() {
        val root = root()
        val runtime =
            ResetRuntimeScript(
                root,
                closedRetirementSteps() +
                    listOf(
                        ResetScriptStep.Activate(LifecycleChildObservation.DeadlineExceeded),
                        ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
                        ResetScriptStep.Job(true, true, ResetCommandObservation.Unavailable),
                    ),
            )
        val result =
            forceResetInstallation(
                root,
                home(),
                emptyMap(),
                ForceResetOperation.REINSTALL,
                ForceResetExecution(runtime, freshInstaller(), {}),
            )
        assertEquals(
            ForceResetOutcome.RecoveryRequired(
                ForceResetOperation.REINSTALL,
                ForceResetStage.ACTIVATION,
                ForceResetFailure.ACTIVATION_REJECTED,
                ForceResetFailure.LAUNCH_JOB_REJECTED,
                root.toString(),
            ),
            result,
        )
        runtime.assertConsumed()
        assertTrue(Files.exists(installationResetFence(root)))
    }

    @Test
    fun `readiness without a valid fresh generation cannot become active`() {
        val root = root()
        val runtime =
            ResetRuntimeScript(
                root,
                closedRetirementSteps() +
                    listOf(
                        ResetScriptStep.Activate(LifecycleChildObservation.Exited(0)),
                        ResetScriptStep.Ready(ResetActivationObservation.Ready("1.2.3", "unknown-generation")),
                    ) +
                    closedRetirementSteps(),
            )
        val result =
            forceResetInstallation(
                root,
                home(),
                emptyMap(),
                ForceResetOperation.REINSTALL,
                ForceResetExecution(runtime, freshInstaller(), {}),
            )
        assertEquals(
            ForceResetOutcome.Pending(
                root.resolve("installation").toString(),
                "1.2.3",
                root.resolve("bin/kast").toString(),
                ForceResetFailure.READINESS_REJECTED,
            ),
            result,
        )
        runtime.assertConsumed()
        assertTrue(Files.exists(installationResetFence(root)))
    }

    private fun successfulReplacementObservations(): List<ResetObservation> =
        listOf(
                ForceResetStage.FENCE,
                ForceResetStage.DAEMONS,
                ForceResetStage.CLEANUP,
                ForceResetStage.INSTALLATION,
                ForceResetStage.ACTIVATION,
            )
            .flatMap {
                listOf(
                    ResetObservation.Started(ForceResetOperation.REINSTALL, it),
                    ResetObservation.Completed(ForceResetOperation.REINSTALL, it),
                )
            }

    private fun freshInstaller(): ForceReinstaller = ForceReinstaller { erased, _, _ ->
        assertEquals(ResetEffect.Completed, erased.stagingAdmission())
        assertFalse(Files.exists(erased.root))
        assertTrue(Files.exists(erased.fence.marker))
        prepareFreshPayload(erased.root)
        val report = temporary.resolve("staged-report.json")
        writeStagedReport(report)
        FreshResetInstallation.admit(erased.root, report)
    }

    private fun home(): Path = Files.createDirectories(temporary.resolve("home"))

    private fun root(): Path = temporary.toRealPath().resolve("kast")

    companion object {
        private const val GENERATION = "12345678-1234-1234-1234-123456789abc"
    }
}
