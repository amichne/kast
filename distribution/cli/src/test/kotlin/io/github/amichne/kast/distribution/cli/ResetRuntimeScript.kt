package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

internal sealed interface ResetScriptStep {
    data class Job(val login: Boolean, val inspect: Boolean, val result: ResetCommandObservation) : ResetScriptStep

    data class Table(val result: ResetProcessTable) : ResetScriptStep

    data class Signal(val snapshot: LifecycleProcessSnapshot, val result: LifecycleProcessObservation) : ResetScriptStep

    data class Activate(val result: LifecycleChildObservation) : ResetScriptStep

    data class Ready(val result: ResetActivationObservation) : ResetScriptStep
}

internal fun closedRetirementSteps(): List<ResetScriptStep> =
    listOf(
        ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(113)),
        ResetScriptStep.Job(false, true, ResetCommandObservation.Exited(113)),
        ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
        ResetScriptStep.Table(ResetProcessTable.Observed(emptyList())),
    )

internal class ResetRuntimeScript(private val root: Path, steps: List<ResetScriptStep>) : ForceDaemonRuntime {
    private val pending = ArrayDeque(steps)

    private fun next(): ResetScriptStep = pending.removeFirstOrNull() ?: error("unexpected reset boundary call")

    fun assertConsumed() = assertTrue(pending.isEmpty(), "unconsumed reset observations: $pending")

    override fun launch(command: ResetLaunchCommand, home: Path): ResetCommandObservation {
        val step = next() as ResetScriptStep.Job
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

    override fun processes(): ResetProcessTable = (next() as ResetScriptStep.Table).result

    override fun retire(process: ResetScopedProcess, allowance: Duration): LifecycleProcessObservation {
        val step = next() as ResetScriptStep.Signal
        assertEquals(step.snapshot, process.snapshot)
        assertTrue(allowance > Duration.ZERO)
        return step.result
    }

    override fun activate(installation: Path, home: Path, environment: Map<String, String>): LifecycleChildObservation {
        assertEquals(root.resolve("installation"), installation)
        assertTrue(
            Files.exists(io.github.amichne.kast.distribution.contract.installationResetFence(root)),
            "tools must remain fenced during bootstrap",
        )
        return (next() as ResetScriptStep.Activate).result
    }

    override fun readiness(installation: Path): ResetActivationObservation {
        assertEquals(root.resolve("installation"), installation)
        assertTrue(
            Files.exists(io.github.amichne.kast.distribution.contract.installationResetFence(root)),
            "tools must remain fenced until readiness",
        )
        return (next() as ResetScriptStep.Ready).result
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
