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

class ForceColdInstallationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `reinstallation completes the qualified payload without a service generation`() {
        val root = temporary.toRealPath().resolve("kast")
        val home = Files.createDirectories(temporary.resolve("home"))
        val runtime = ResetRuntimeScript(root, closedRetirementSteps())
        val result =
            forceResetInstallation(
                root,
                home,
                emptyMap(),
                ForceResetOperation.REINSTALL,
                ForceResetExecution(runtime, installer(), {}),
            )
        assertEquals(
            ForceResetOutcome.Installed(
                root.resolve("installation").toString(),
                "1.2.3",
                root.resolve("bin/kast").toString(),
            ),
            result,
        )
        runtime.assertConsumed()
        assertFalse(Files.exists(installationResetFence(root)))
    }

    @Test
    fun `cold completion rejects a replacement fence without deleting it`() {
        withStaged { staged ->
            val marker = staged.erased.fence.marker
            val content = Files.readString(marker)
            Files.move(marker, marker.resolveSibling("prior-marker"))
            Files.writeString(marker, content)
            assertEquals(ResetInstallation.Rejected(ForceResetFailure.FENCE_REJECTED), InstalledReset.complete(staged))
            assertEquals(content, Files.readString(marker))
        }
    }

    @Test
    fun `cold completion rejects a changed payload and keeps the fence`() {
        withStaged { staged ->
            Files.writeString(staged.payload.installation.resolve("share/kast/libexec/kast-service"), "changed")
            assertEquals(
                ResetInstallation.Rejected(ForceResetFailure.INSTALLATION_UNVERIFIED),
                InstalledReset.complete(staged),
            )
            assertTrue(Files.exists(staged.erased.fence.marker))
        }
    }

    @Test
    fun `cold completion cannot replay the consumed staged state`() {
        withStaged { staged ->
            org.junit.jupiter.api.Assertions.assertInstanceOf(
                ResetInstallation.Installed::class.java,
                InstalledReset.complete(staged),
            )
            assertEquals(ResetInstallation.Rejected(ForceResetFailure.RESET_BUSY), InstalledReset.complete(staged))
            assertFalse(Files.exists(staged.erased.fence.marker))
        }
    }

    @Test
    fun `rejected cold completion keeps the fence after retirement`() {
        val root = temporary.toRealPath().resolve("kast")
        val home = Files.createDirectories(temporary.resolve("home"))
        val runtime = ResetRuntimeScript(root, closedRetirementSteps() + closedRetirementSteps())
        val outcome =
            forceResetInstallation(
                root,
                home,
                emptyMap(),
                ForceResetOperation.REINSTALL,
                ForceResetExecution(runtime, changedInstaller(), {}),
            )
        assertEquals(
            ForceResetOutcome.Pending(
                root.resolve("installation").toString(),
                "1.2.3",
                root.resolve("bin/kast").toString(),
                ForceResetFailure.INSTALLATION_UNVERIFIED,
            ),
            outcome,
        )
        runtime.assertConsumed()
        assertTrue(Files.exists(installationResetFence(root)))
    }

    @Test
    fun `failed cold compensation preserves payload and retirement failures`() {
        val root = temporary.toRealPath().resolve("kast")
        val home = Files.createDirectories(temporary.resolve("home"))
        val runtime =
            ResetRuntimeScript(
                root,
                closedRetirementSteps() +
                    listOf(
                        ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
                        ResetScriptStep.Job(true, true, ResetCommandObservation.Unavailable),
                    ),
            )
        val outcome =
            forceResetInstallation(
                root,
                home,
                emptyMap(),
                ForceResetOperation.REINSTALL,
                ForceResetExecution(runtime, changedInstaller(), {}),
            )
        assertEquals(
            ForceResetOutcome.RecoveryRequired(
                ForceResetOperation.REINSTALL,
                ForceResetStage.FINAL_VERIFICATION,
                ForceResetFailure.INSTALLATION_UNVERIFIED,
                ForceResetFailure.LAUNCH_JOB_REJECTED,
                root.toString(),
            ),
            outcome,
        )
        runtime.assertConsumed()
        assertTrue(Files.exists(installationResetFence(root)))
    }

    private fun changedInstaller() = ForceReinstaller { erased, home, environment ->
        val admitted = installer().stage(erased, home, environment)
        Files.writeString(erased.root.resolve("installation/share/kast/libexec/kast-service"), "changed")
        admitted
    }

    private fun withStaged(action: (StagedReset) -> Unit) {
        val root = temporary.toRealPath().resolve("kast")
        val home = Files.createDirectories(temporary.resolve("home"))
        ErasedResetFixture.create(root, home).use { fixture ->
            val result = StagedReset.stage(fixture.erased, home, emptyMap(), installer())
            val staged =
                org.junit.jupiter.api.Assertions.assertInstanceOf(ResetStaging.Staged::class.java, result).reset
            action(staged)
        }
    }

    private fun installer() = ForceReinstaller { erased, _, _ ->
        prepareFreshPayload(erased.root)
        val report = temporary.resolve("staged-report.json")
        writeStagedReport(report)
        FreshResetInstallation.admit(erased.root, report)
    }
}
