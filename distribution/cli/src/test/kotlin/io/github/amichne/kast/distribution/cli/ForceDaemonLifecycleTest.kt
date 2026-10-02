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
    fun `launch job that survives bootout prevents deletion and preserves prior payload`() {
        val home = home()
        val root = root()
        Files.createDirectories(root)
        Files.writeString(root.resolve("sentinel"), "prior payload")
        val runtime =
            ResetRuntimeScript(
                root,
                listOf(
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
    fun `observing the same process incarnation after force termination refuses quiescence`() {
        val root = root()
        val prior = snapshot(root)
        val runtime =
            ResetRuntimeScript(
                root,
                absentJobs() +
                    listOf(
                        ResetScriptStep.Table(ResetProcessTable.Observed(listOf(prior))),
                        ResetScriptStep.Signal(prior, LifecycleProcessObservation.Present(prior)),
                    ),
            )
        val result = ForceDaemonRetirement(runtime).retire(root, home())
        assertEquals(ResetEffect.Rejected(ForceResetFailure.PROCESS_RETIREMENT_REJECTED), result)
        runtime.assertConsumed()
    }

    @Test
    fun `a reused PID may be retained when the replacement incarnation is unrelated`() {
        val root = root()
        val prior = snapshot(root)
        val replacement =
            prior.copy(
                startEpochMillis = prior.startEpochMillis + 1,
                command = "/usr/bin/unrelated",
                arguments = emptyList(),
            )
        val runtime =
            ResetRuntimeScript(
                root,
                absentJobs() +
                    listOf(
                        ResetScriptStep.Table(ResetProcessTable.Observed(listOf(prior))),
                        ResetScriptStep.Signal(prior, LifecycleProcessObservation.Present(replacement)),
                        ResetScriptStep.Table(ResetProcessTable.Observed(listOf(replacement))),
                    ),
            )
        assertEquals(ResetEffect.Completed, ForceDaemonRetirement(runtime).retire(root, home()))
        runtime.assertConsumed()
    }

    @Test
    fun `observation of a different PID cannot establish retirement of the selected PID`() {
        val root = root()
        val prior = snapshot(root)
        val other = prior.copy(pid = prior.pid + 1, startEpochMillis = prior.startEpochMillis + 1)
        val runtime =
            ResetRuntimeScript(
                root,
                absentJobs() +
                    listOf(
                        ResetScriptStep.Table(ResetProcessTable.Observed(listOf(prior))),
                        ResetScriptStep.Signal(prior, LifecycleProcessObservation.Present(other)),
                    ),
            )
        assertEquals(
            ResetEffect.Rejected(ForceResetFailure.PROCESS_RETIREMENT_REJECTED),
            ForceDaemonRetirement(runtime).retire(root, home()),
        )
        runtime.assertConsumed()
    }

    @Test
    fun `a scoped process returning during final observation prevents quiescence`() {
        val root = root()
        val prior = snapshot(root)
        val runtime =
            ResetRuntimeScript(
                root,
                absentJobs() +
                    listOf(
                        ResetScriptStep.Table(ResetProcessTable.Observed(listOf(prior))),
                        ResetScriptStep.Signal(prior, LifecycleProcessObservation.Absent),
                        ResetScriptStep.Table(
                            ResetProcessTable.Observed(listOf(prior.copy(pid = 92, startEpochMillis = 300)))
                        ),
                    ),
            )
        assertEquals(
            ResetEffect.Rejected(ForceResetFailure.PROCESS_RETIREMENT_REJECTED),
            ForceDaemonRetirement(runtime).retire(root, home()),
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
