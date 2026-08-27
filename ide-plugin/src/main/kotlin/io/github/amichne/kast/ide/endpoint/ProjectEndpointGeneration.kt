package io.github.amichne.kast.ide.endpoint

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.metadata.IdeRuntimeEpoch

private sealed interface ProjectEndpointGenerationState {
    data class Available(
        val generation: ProjectEndpointGeneration,
    ) : ProjectEndpointGenerationState

    data object Exhausted : ProjectEndpointGenerationState
}

@JvmInline
private value class ProjectEndpointGeneration(val value: Long)

internal sealed interface ProjectEndpointGenerationIssuance {
    data class Issued(
        val epoch: IdeRuntimeEpoch,
    ) : ProjectEndpointGenerationIssuance

    data object Exhausted : ProjectEndpointGenerationIssuance
}

/** Monotonic Project-service endpoint generation authority; readiness probes do not consume it. */
internal class ProjectEndpointGenerationSource private constructor(
    private var state: ProjectEndpointGenerationState,
) {
    constructor() : this(ProjectEndpointGenerationState.Available(ProjectEndpointGeneration(1)))

    /**
     * Proof transition: `ProjectEndpointGenerationSource -> ProjectEndpointGenerationIssuance`.
     *
     * Issues one monotonic non-negative [IdeRuntimeEpoch] immediately before endpoint preparation,
     * or the closed [ProjectEndpointGenerationIssuance.Exhausted] terminal state.
     */
    @Synchronized
    fun issue(): ProjectEndpointGenerationIssuance = when (val current = state) {
        ProjectEndpointGenerationState.Exhausted -> ProjectEndpointGenerationIssuance.Exhausted
        is ProjectEndpointGenerationState.Available -> {
            state = if (current.generation.value == Long.MAX_VALUE) {
                ProjectEndpointGenerationState.Exhausted
            } else {
                ProjectEndpointGenerationState.Available(
                    ProjectEndpointGeneration(current.generation.value + 1),
                )
            }
            when (val parsed = IdeRuntimeEpoch.parse(current.generation.value)) {
                is Refinement.Refined -> ProjectEndpointGenerationIssuance.Issued(parsed.value)
                is Refinement.Rejected -> ProjectEndpointGenerationIssuance.Exhausted
            }
        }
    }

    internal companion object {
        @JvmSynthetic
        fun testing(first: IdeRuntimeEpoch): ProjectEndpointGenerationSource =
            ProjectEndpointGenerationSource(
                ProjectEndpointGenerationState.Available(ProjectEndpointGeneration(first.value)),
            )
    }
}
