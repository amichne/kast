package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandFailure
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationFailure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface WorkspaceDemandFailureDocument {
    @Serializable
    @SerialName("admission")
    data class Admission(val root: String, val failure: WorkspacePreparationFailure) : WorkspaceDemandFailureDocument

    @Serializable
    @SerialName("operation")
    data class Operation(val requestId: String, val root: String, val cause: WorkspaceDemandCause) :
        WorkspaceDemandFailureDocument

    companion object {
        fun from(failure: WorkspaceDemandFailure): WorkspaceDemandFailureDocument =
            when (failure) {
                is WorkspaceDemandFailure.Admission -> Admission(failure.root.path.toString(), failure.failure)
                is WorkspaceDemandFailure.Operation ->
                    Operation(failure.id.value.toString(), failure.root.path.toString(), failure.cause)
            }
    }
}
