package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Historical operation observation, never a substitute for fresh native request admission. */
@Serializable
internal data class WorkspacePreparationDocument(
    val requestId: String,
    val root: String,
    val outcome: WorkspacePreparationState,
)

@Serializable
internal sealed interface WorkspacePreparationState {
    @Serializable @SerialName("pending") data class Pending(val stage: IdeLifecycleStage) : WorkspacePreparationState

    @Serializable
    @SerialName("completed")
    data class Completed(val target: IdeProjectTarget) : WorkspacePreparationState

    @Serializable
    @SerialName("rejected")
    data class Rejected(val failure: WorkspacePreparationFailure) : WorkspacePreparationState

    @Serializable @SerialName("blocked") data class Blocked(val reason: IdeLifecycleFailure) : WorkspacePreparationState
}

internal fun WorkspacePreparation.document(): WorkspacePreparationDocument =
    WorkspacePreparationDocument(
        id.value.toString(),
        root.path.toString(),
        when (val value = state.value) {
            is WorkspacePreparationOutcome.Pending -> WorkspacePreparationState.Pending(value.stage)
            is WorkspacePreparationOutcome.Complete -> WorkspacePreparationState.Completed(value.workspace.target)
            is WorkspacePreparationOutcome.Rejected -> WorkspacePreparationState.Rejected(value.failure)
            is WorkspacePreparationOutcome.Blocked -> WorkspacePreparationState.Blocked(value.reason)
        },
    )
