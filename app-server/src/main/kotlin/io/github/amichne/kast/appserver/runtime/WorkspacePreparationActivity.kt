package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import java.io.PrintStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class WorkspacePreparationActivity(
    val requestId: String,
    val outcome: WorkspacePreparationActivityOutcome,
    val event: String = "workspace-preparation",
)

/** Bounded stage and terminal evidence; contains no roots, requests, source, or approval payloads. */
@Serializable
internal sealed interface WorkspacePreparationActivityOutcome {
    @Serializable
    @SerialName("pending")
    data class Pending(val stage: IdeLifecycleStage) : WorkspacePreparationActivityOutcome

    @Serializable @SerialName("completed") data object Completed : WorkspacePreparationActivityOutcome

    @Serializable
    @SerialName("rejected")
    data class Rejected(val failure: WorkspacePreparationFailure) : WorkspacePreparationActivityOutcome

    @Serializable
    @SerialName("blocked")
    data class Blocked(val reason: IdeLifecycleFailure) : WorkspacePreparationActivityOutcome
}

internal fun interface WorkspacePreparationObserver {
    fun observe(activity: WorkspacePreparationActivity)

    data object Disabled : WorkspacePreparationObserver {
        override fun observe(activity: WorkspacePreparationActivity) = Unit
    }
}

internal class JsonLineWorkspacePreparationObserver(private val output: PrintStream) : WorkspacePreparationObserver {
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun observe(activity: WorkspacePreparationActivity) {
        output.println(json.encodeToString(WorkspacePreparationActivity.serializer(), activity))
    }
}

internal fun WorkspacePreparation.activity(): WorkspacePreparationActivity =
    WorkspacePreparationActivity(
        id.value.toString(),
        when (val value = state.value) {
            is WorkspacePreparationOutcome.Pending -> WorkspacePreparationActivityOutcome.Pending(value.stage)
            is WorkspacePreparationOutcome.Complete -> WorkspacePreparationActivityOutcome.Completed
            is WorkspacePreparationOutcome.Rejected -> WorkspacePreparationActivityOutcome.Rejected(value.failure)
            is WorkspacePreparationOutcome.Blocked -> WorkspacePreparationActivityOutcome.Blocked(value.reason)
        },
    )
