package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
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
