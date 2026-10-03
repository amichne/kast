package io.github.amichne.kast.protocol.contract

/** Exact finite request, execution and presentation failures share one canonical outcome owner. */
sealed interface QueryRunRejection : QueryRunFailure {
    data class ImpactExecutionRejected(val cause: ImpactExecutionFailureDocument) : QueryRunRejection

    data class ImpactSourceRejected(val cause: QueryImpactSourceFailureDocument) : QueryRunRejection

    data class ImpactPresentationRejected(val cause: ImpactPresentationFailureDocument) : QueryRunRejection

    data object WorkspaceNotReady : QueryRunRejection

    data class ReferenceRejected(
        val position: ProtocolOffset,
        val reason: QueryReferenceRejectionReason,
    ) : QueryRunRejection

    data class StepReferenceRejected(
        val stepPosition: ProtocolOffset,
        val referencePosition: ProtocolOffset,
        val reason: QueryReferenceRejectionReason,
    ) : QueryRunRejection

    data class SourceRejected(
        val kind: QueryDeclarationKindDocument,
        val reason: QuerySourceRejectionReason,
    ) : QueryRunRejection

    data class ExecutionRejected(val reason: QueryExecutionRejectionDocument) : QueryRunRejection
}
