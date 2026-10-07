package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.QueryRunRejection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface QueryRejectionCliDocument {
    @Serializable
    @SerialName("COMPLETION_UNSUPPORTED")
    data class CompletionUnsupported(val detail: QueryRunRejection.CompletionUnsupported) : QueryRejectionCliDocument

    @Serializable
    @SerialName("COMPLETION_UNPROVEN")
    data class CompletionUnproven(val detail: QueryRunRejection.CompletionUnproven) : QueryRejectionCliDocument

    @Serializable
    @SerialName("IMPACT_EXECUTION_REJECTED")
    data class ImpactExecutionRejected(
        val cause: io.github.amichne.kast.protocol.contract.ImpactExecutionFailureDocument
    ) : QueryRejectionCliDocument

    @Serializable
    @SerialName("IMPACT_SOURCE_REJECTED")
    data class ImpactSourceRejected(
        val cause: io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
    ) : QueryRejectionCliDocument

    @Serializable
    @SerialName("IMPACT_PRESENTATION_REJECTED")
    data class ImpactPresentationRejected(
        val cause: io.github.amichne.kast.protocol.contract.ImpactPresentationFailureDocument
    ) : QueryRejectionCliDocument

    @Serializable @SerialName("workspace-not-ready") data object WorkspaceNotReady : QueryRejectionCliDocument

    @Serializable
    @SerialName("reference-rejected")
    data class ReferenceRejected(
        val path: String,
        @Serializable(with = QueryReferenceRejectionCliSerializer::class)
        val reason: io.github.amichne.kast.protocol.contract.QueryReferenceRejectionReason,
    ) : QueryRejectionCliDocument

    @Serializable
    @SerialName("source-rejected")
    data class SourceRejected(
        @io.github.amichne.kast.protocol.contract.ProtocolAllowedValues("constructor")
        @Serializable(with = QueryDeclarationKindCliSerializer::class)
        val kind: io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument,
        @Serializable(with = QuerySourceRejectionCliSerializer::class)
        val reason: io.github.amichne.kast.protocol.contract.QuerySourceRejectionReason,
    ) : QueryRejectionCliDocument

    @Serializable
    @SerialName("execution-rejected")
    data class ExecutionRejected(
        @Serializable(with = QueryExecutionRejectionCliSerializer::class)
        val reason: io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
    ) : QueryRejectionCliDocument
}

internal fun QueryRunRejection.toCliDocument(): QueryRejectionCliDocument =
    when (this) {
        is QueryRunRejection.CompletionUnsupported -> QueryRejectionCliDocument.CompletionUnsupported(this)
        is QueryRunRejection.CompletionUnproven -> QueryRejectionCliDocument.CompletionUnproven(this)
        is QueryRunRejection.ImpactSourceRejected -> QueryRejectionCliDocument.ImpactSourceRejected(cause)
        is QueryRunRejection.ImpactExecutionRejected -> QueryRejectionCliDocument.ImpactExecutionRejected(cause)
        is QueryRunRejection.ImpactPresentationRejected -> QueryRejectionCliDocument.ImpactPresentationRejected(cause)
        QueryRunRejection.WorkspaceNotReady -> QueryRejectionCliDocument.WorkspaceNotReady
        is QueryRunRejection.ReferenceRejected ->
            QueryRejectionCliDocument.ReferenceRejected(
                "from.values[${position.value}]",
                reason,
            )
        is QueryRunRejection.StepReferenceRejected ->
            QueryRejectionCliDocument.ReferenceRejected(
                "steps[${stepPosition.value}].input.values[${referencePosition.value}]",
                reason,
            )
        is QueryRunRejection.SourceRejected ->
            QueryRejectionCliDocument.SourceRejected(
                kind,
                reason,
            )
        is QueryRunRejection.ExecutionRejected -> QueryRejectionCliDocument.ExecutionRejected(reason)
    }
