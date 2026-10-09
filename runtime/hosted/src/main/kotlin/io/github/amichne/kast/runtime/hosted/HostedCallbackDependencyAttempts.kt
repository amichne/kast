package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.topology.intellij.SemanticDependencyCaptureFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** One request's optional preparation decision. No successful snapshot survives its native read action. */
internal class HostedCallbackDependencyAttempts(private val observation: IntellijReadObservation) {
    private sealed interface State {
        data object Ready : State

        data class Unavailable(val cause: SemanticDependencyCaptureFailure) : State
    }

    private var state: State = State.Ready

    /** A rejection disables optional reuse for this request; ordinary fresh extraction remains available. */
    @Synchronized
    fun capture(
        effect: () -> Refinement<SemanticDependencySnapshot, SemanticDependencyCaptureFailure>
    ): Refinement<SemanticDependencySnapshot, SemanticDependencyCaptureFailure> =
        when (val current = state) {
            is State.Unavailable -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_PREPARATIONS_SKIPPED)
                Refinement.Rejected(current.cause)
            }
            State.Ready -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS)
                when (val result = effect()) {
                    is Refinement.Refined -> result
                    is Refinement.Rejected -> {
                        state = State.Unavailable(result.failure)
                        observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
                        result
                    }
                }
            }
        }
}
