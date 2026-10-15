package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue

internal sealed interface ResetScriptStep {
    data class Job(val login: Boolean, val inspect: Boolean, val result: ResetCommandObservation) : ResetScriptStep

    data class Table(val result: ResetProcessTable) : ResetScriptStep

    data class Capture(val snapshot: LifecycleProcessSnapshot, val result: ResetScriptCaptureResult) : ResetScriptStep

    data class Signal(val snapshot: LifecycleProcessSnapshot, val result: LifecycleProcessObservation) : ResetScriptStep

    data class Activate(val result: LifecycleChildObservation) : ResetScriptStep

    data class Ready(val result: ResetActivationObservation) : ResetScriptStep
}

internal enum class ResetScriptCaptureResult {
    NO_SHARED_DESCRIPTORS,
    REJECTED,
}

internal fun closedRetirementSteps(): List<ResetScriptStep> =
    listOf(
        ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
        ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(113)),
        ResetScriptStep.Job(false, true, ResetCommandObservation.Exited(113)),
        ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
    )

internal class ResetRuntimeScript(private val root: Path, steps: List<ResetScriptStep>) : ForceDaemonRuntime {
    private val pending = ArrayDeque(steps)
    private val captured = ArrayDeque<ResetSharedSocketCapture.Captured>()

    private inline fun <reified T : ResetScriptStep> next(): T {
        val step = pending.removeFirstOrNull()
        assertTrue(step is T, "expected ${T::class.simpleName}, received $step")
        return step as T
    }

    fun assertConsumed() = assertTrue(pending.isEmpty(), "unconsumed reset observations: $pending")

    override fun launch(command: ResetLaunchCommand, home: Path): ResetCommandObservation {
        val step = next<ResetScriptStep.Job>()
        assertEquals(ResetServiceLabel.selected(root.resolve("installation")), command.label)
        when (command) {
            is ResetLaunchCommand.Inspect -> {
                assertTrue(step.inspect)
                assertEquals(step.login, command.variant == ResetLaunchVariant.LEGACY_LOGIN)
            }
            is ResetLaunchCommand.Bootout -> {
                assertTrue(!step.inspect)
                assertEquals(step.login, command.variant == ResetLaunchVariant.LEGACY_LOGIN)
            }
        }
        return step.result
    }

    override fun processes(): ResetProcessTable = next<ResetScriptStep.Table>().result

    override fun capture(process: ResetScopedProcess): ResetSharedSocketCapture {
        val step = next<ResetScriptStep.Capture>()
        assertEquals(step.snapshot, process.snapshot)
        return when (step.result) {
            ResetScriptCaptureResult.NO_SHARED_DESCRIPTORS ->
                ResetSharedSocketCapture.Captured(process, emptyList()).also(captured::addLast)
            ResetScriptCaptureResult.REJECTED -> ResetSharedSocketCapture.Rejected
        }
    }

    override fun retire(capture: ResetSharedSocketCapture.Captured, allowance: Duration): LifecycleProcessObservation {
        val step = next<ResetScriptStep.Signal>()
        assertTrue(captured.isNotEmpty(), "retirement needs a scripted capture")
        assertSame(captured.removeFirst(), capture)
        assertEquals(step.snapshot, capture.process.snapshot)
        assertTrue(allowance > Duration.ZERO)
        return step.result
    }

    override fun activate(installation: Path, home: Path, environment: Map<String, String>): LifecycleChildObservation {
        assertEquals(root.resolve("installation"), installation)
        assertTrue(
            Files.exists(io.github.amichne.kast.distribution.contract.installationResetFence(root)),
            "tools must remain fenced during bootstrap",
        )
        return next<ResetScriptStep.Activate>().result
    }

    override fun readiness(installation: Path): ResetActivationObservation {
        assertEquals(root.resolve("installation"), installation)
        assertTrue(
            Files.exists(io.github.amichne.kast.distribution.contract.installationResetFence(root)),
            "tools must remain fenced until readiness",
        )
        return next<ResetScriptStep.Ready>().result
    }
}

internal class ErasedResetFixture private constructor(val erased: ErasedReset) : AutoCloseable {
    companion object {
        fun create(root: Path, home: Path): ErasedResetFixture {
            val selected = (ForceResetRoot.admit(root, home) as ForceResetRootAdmission.Selected).root
            val fence = (ForceResetFence.acquire(selected) as ResetFenceAcquisition.Acquired).fence
            val fenced = (FencedReset.detach(fence) as ResetFencing.Fenced).reset
            val runtime = ResetRuntimeScript(selected.path, closedRetirementSteps())
            val retired = (QuiescentReset.retire(fenced, home, runtime) as ResetRetirement.Retired).reset
            runtime.assertConsumed()
            val erased = (ErasedReset.erase(retired) as ResetErasure.Erased).reset
            return ErasedResetFixture(erased)
        }
    }

    override fun close() {
        erased.fence.close()
        Files.deleteIfExists(erased.fence.marker)
    }
}
