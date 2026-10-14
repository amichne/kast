package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ResetProcessRetirementTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `observing the same process incarnation after force termination refuses quiescence`() {
        val root = root()
        val prior = snapshot(root)
        val runtime =
            ResetRuntimeScript(
                root,
                listOf(
                    ResetScriptStep.Table(ResetProcessTable.Observed(listOf(prior))),
                    ResetScriptStep.Capture(prior, ResetScriptCaptureResult.NO_SHARED_DESCRIPTORS),
                ) + absentJobs() + listOf(ResetScriptStep.Signal(prior, LifecycleProcessObservation.Present(prior))),
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
                listOf(
                    ResetScriptStep.Table(ResetProcessTable.Observed(listOf(prior))),
                    ResetScriptStep.Capture(prior, ResetScriptCaptureResult.NO_SHARED_DESCRIPTORS),
                ) +
                    absentJobs() +
                    listOf(
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
                listOf(
                    ResetScriptStep.Table(ResetProcessTable.Observed(listOf(prior))),
                    ResetScriptStep.Capture(prior, ResetScriptCaptureResult.NO_SHARED_DESCRIPTORS),
                ) + absentJobs() + listOf(ResetScriptStep.Signal(prior, LifecycleProcessObservation.Present(other))),
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
                listOf(
                    ResetScriptStep.Table(ResetProcessTable.Observed(listOf(prior))),
                    ResetScriptStep.Capture(prior, ResetScriptCaptureResult.NO_SHARED_DESCRIPTORS),
                ) +
                    absentJobs() +
                    listOf(
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
