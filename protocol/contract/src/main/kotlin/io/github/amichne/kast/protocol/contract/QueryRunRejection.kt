package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Exact finite request, execution and presentation failures share one canonical outcome owner. */
sealed interface QueryRunRejection : QueryRunFailure {
    @Serializable
    @SerialName("COMPLETION_UNSUPPORTED")
    data class CompletionUnsupported(
        val model: QueryStaticModelDocument,
        val reason: QueryCompletionUnsupportedReason,
    ) : QueryRunRejection

    data class CompletionUnproven(
        val model: QueryStaticModelDocument,
        val cause: QueryCompletionCauseDocument,
        val originalCoverage: QueryCompletionCoverageDocument,
        val stop: QueryInvocationStop,
        val evidence: QueryCompletionEvidenceDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
        val policyProgress: QueryCompletionPolicyProgressDocument = QueryCompletionPolicyProgressDocument.EvidenceOnly,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val originalFailure: QueryOriginalFailureDocument? = null,
        val diagnosticReadId: QueryDiagnosticReadIdentity? = null,
    ) : QueryRunRejection {
        val reason
            get() = cause.reason

        val callbackGraphFailure
            get() = (cause as? QueryCompletionCauseDocument.CallbackGraphUnproven)?.graphFailure
    }

    @Serializable
    @SerialName("IMPACT_EXECUTION")
    data class ImpactExecutionRejected(val cause: ImpactExecutionFailureDocument) : QueryOriginalFailureDocument

    @Serializable
    @SerialName("IMPACT_SOURCE")
    data class ImpactSourceRejected(val cause: QueryImpactSourceFailureDocument) : QueryOriginalFailureDocument

    @Serializable
    @SerialName("IMPACT_PRESENTATION")
    data class ImpactPresentationRejected(val cause: ImpactPresentationFailureDocument) : QueryOriginalFailureDocument

    @Serializable @SerialName("WORKSPACE_NOT_READY") data object WorkspaceNotReady : QueryOriginalFailureDocument

    @Serializable
    @SerialName("REFERENCE")
    data class ReferenceRejected(
        val position: ProtocolOffset,
        val reason: QueryReferenceRejectionReason,
    ) : QueryOriginalFailureDocument

    @Serializable
    @SerialName("STEP_REFERENCE")
    data class StepReferenceRejected(
        val stepPosition: ProtocolOffset,
        val referencePosition: ProtocolOffset,
        val reason: QueryReferenceRejectionReason,
    ) : QueryOriginalFailureDocument

    @Serializable
    @SerialName("SOURCE")
    data class SourceRejected(
        val kind: QueryDeclarationKindDocument,
        val reason: QuerySourceRejectionReason,
    ) : QueryOriginalFailureDocument

    @Serializable
    @SerialName("EXECUTION")
    data class ExecutionRejected(val reason: QueryExecutionRejectionDocument) : QueryOriginalFailureDocument
}
