package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.topology.intellij.SemanticDependencyCaptureFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

/** One request's optional preparation decision. No successful snapshot survives its native read action. */
internal class HostedCallbackDependencyAttempts(
    private val observation: IntellijReadObservation,
    allowed: Set<HostedCallbackDependencyUniverse> = setOf(HostedCallbackDependencyUniverse.WholeWorkspace),
) {
    private val allowed = allowed.toSet()

    private sealed interface State {
        data object Ready : State

        data class Unavailable(val cause: SemanticDependencyCaptureFailure) : State
    }

    private val states = mutableMapOf<HostedCallbackDependencyUniverse, State>()

    /** A rejection disables optional reuse for this request; ordinary fresh extraction remains available. */
    @Synchronized
    fun capture(
        universe: HostedCallbackDependencyUniverse = HostedCallbackDependencyUniverse.WholeWorkspace,
        effect: () -> Refinement<SemanticDependencySnapshot, SemanticDependencyCaptureFailure>,
    ): Refinement<SemanticDependencySnapshot, SemanticDependencyCaptureFailure> {
        if (universe !in allowed) {
            observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
            observation.terminated(IntellijReadTermination.SEMANTIC_INPUT_DEPENDENCY_MODULE_UNMODELED)
            return Refinement.Rejected(SemanticDependencyCaptureFailure.DEPENDENCY_MODULE_UNMODELED)
        }
        return when (val current = states[universe] ?: State.Ready) {
            is State.Unavailable -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_PREPARATIONS_SKIPPED)
                Refinement.Rejected(current.cause)
            }
            State.Ready -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS)
                when (val result = effect()) {
                    is Refinement.Refined -> result
                    is Refinement.Rejected -> {
                        states[universe] = State.Unavailable(result.failure)
                        observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
                        result
                    }
                }
            }
        }
    }
}
