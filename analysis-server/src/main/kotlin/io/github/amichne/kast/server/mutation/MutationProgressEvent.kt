package io.github.amichne.kast.server.mutation

import io.github.amichne.kast.api.contract.mutation.KastMutationProgressStage

internal sealed interface MutationProgressEvent {
    data class StageEntered(
        val stage: KastMutationProgressStage,
    ) : MutationProgressEvent

    data object EditApplicationCompleted : MutationProgressEvent
}
