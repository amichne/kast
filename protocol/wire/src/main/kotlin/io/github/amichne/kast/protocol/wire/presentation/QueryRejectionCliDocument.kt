package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.QueryRunRejection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface QueryRejectionCliDocument {
    @Serializable @SerialName("workspace-not-ready") data object WorkspaceNotReady : QueryRejectionCliDocument

    @Serializable
    @SerialName("reference-rejected")
    data class ReferenceRejected(val path: String, val reason: String) : QueryRejectionCliDocument

    @Serializable
    @SerialName("source-rejected")
    data class SourceRejected(val kind: String, val reason: String) : QueryRejectionCliDocument

    @Serializable
    @SerialName("execution-rejected")
    data class ExecutionRejected(val reason: String) : QueryRejectionCliDocument
}

internal fun QueryRunRejection.toCliDocument(): QueryRejectionCliDocument =
    when (this) {
        QueryRunRejection.WorkspaceNotReady -> QueryRejectionCliDocument.WorkspaceNotReady
        is QueryRunRejection.ReferenceRejected ->
            QueryRejectionCliDocument.ReferenceRejected(
                "from.values[${position.value}]",
                reason.cliName(),
            )
        is QueryRunRejection.StepReferenceRejected ->
            QueryRejectionCliDocument.ReferenceRejected(
                "steps[${stepPosition.value}].input.values[${referencePosition.value}]",
                reason.cliName(),
            )
        is QueryRunRejection.SourceRejected ->
            QueryRejectionCliDocument.SourceRejected(
                kind.cliName(),
                reason.cliName(),
            )
        is QueryRunRejection.ExecutionRejected -> QueryRejectionCliDocument.ExecutionRejected(reason.cliName())
    }
