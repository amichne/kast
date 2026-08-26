package io.github.amichne.kast.runtime.ide.read

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.ide.read.revalidation.ProjectReadEpochObserver
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import java.nio.file.Path

/** Host-free retained-source observer with an explicit finite revalidation program. */
internal class EpochRevalidationFixture(
    path: String,
) : ProjectReadEpochObserver {
    sealed interface Step {
        data object Current : Step
        data object Moved : Step
        data object Incomparable : Step
        data class Rejected(val failure: ProjectReadEpochObservationFailure) : Step
        data class Cancelled(val cancellation: ProcessCanceledException) : Step
    }

    private val root = when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(path))) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error("test root rejected: ${admitted.failure}")
    }
    private var state = 1
    private val source = ProjectReadEpoch.Source.create<Int> { Refinement.Refined(state) }
    private val foreignSource = ProjectReadEpoch.Source.create<Int> { Refinement.Refined(state) }
    private val program = ArrayDeque<Step>()

    var observations: Int = 0
        private set

    fun capability(): VfsPassiveReadCapability = VfsPassiveReadCapability.issue(
        root,
        source.observedEpoch(),
    )

    fun plan(vararg steps: Step) {
        program.addAll(steps)
    }

    fun advance() {
        state += 1
    }

    override fun observe(): ProjectReadEpochObservation {
        observations += 1
        return when (val step = program.removeFirstOrNull() ?: Step.Current) {
            Step.Current -> source.observe()
            Step.Moved -> {
                advance()
                source.observe()
            }
            Step.Incomparable -> foreignSource.observe()
            is Step.Rejected -> ProjectReadEpochObservation.Rejected(step.failure)
            is Step.Cancelled -> throw step.cancellation
        }
    }
}

private fun ProjectReadEpoch.Source<Int>.observedEpoch(): ProjectReadEpoch<*> = when (
    val observed = observe()
) {
    is ProjectReadEpochObservation.Observed -> observed.epoch
    is ProjectReadEpochObservation.Rejected -> error(
        "test epoch rejected: ${observed.failure}",
    )
}
