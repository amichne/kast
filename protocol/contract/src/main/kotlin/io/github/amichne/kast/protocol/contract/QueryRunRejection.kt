package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Exact finite request, execution and presentation failures share one canonical outcome owner. */
@Serializable
sealed interface QueryRunRejection : QueryRunFailure {
    @Serializable
    @SerialName("IMPACT_EXECUTION")
    data class ImpactExecutionRejected(val cause: ImpactExecutionFailureDocument) : QueryRunRejection

    @Serializable
    @SerialName("IMPACT_SOURCE")
    data class ImpactSourceRejected(val cause: QueryImpactSourceFailureDocument) : QueryRunRejection

    @Serializable
    @SerialName("IMPACT_PRESENTATION")
    data class ImpactPresentationRejected(val cause: ImpactPresentationFailureDocument) : QueryRunRejection

    @Serializable @SerialName("WORKSPACE_NOT_READY") data object WorkspaceNotReady : QueryRunRejection

    @Serializable
    @SerialName("REFERENCE")
    data class ReferenceRejected(
        val position: ProtocolOffset,
        val reason: QueryReferenceRejectionReason,
    ) : QueryRunRejection

    @Serializable
    @SerialName("STEP_REFERENCE")
    data class StepReferenceRejected(
        val stepPosition: ProtocolOffset,
        val referencePosition: ProtocolOffset,
        val reason: QueryReferenceRejectionReason,
    ) : QueryRunRejection

    @Serializable
    @SerialName("SOURCE")
    data class SourceRejected(
        val kind: QueryDeclarationKindDocument,
        val reason: QuerySourceRejectionReason,
    ) : QueryRunRejection

    @Serializable
    @SerialName("EXECUTION")
    data class ExecutionRejected(val reason: QueryExecutionRejectionDocument) : QueryRunRejection
}
