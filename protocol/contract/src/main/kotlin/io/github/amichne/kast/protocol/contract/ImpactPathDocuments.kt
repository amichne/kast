@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

private const val MAX_IMPACT_CATCH_BRANCHES = 1024

/** Detached path evidence. Completion is exclusively carried by the containing query coverage ledger. */
@Serializable
data class ImpactPathDocument(
    val producer: ImpactValueSiteReferenceDocument,
    val steps: BoundedProtocolList<ImpactPathStepDocument>,
    val representation: ImpactRepresentationEvidenceDocument,
    val terminal: ImpactPathTerminalDocument,
)

@Serializable
data class ImpactRuleReferenceDocument(val model: ImpactModelIdentityDocument, val rule: ImpactModelIdentifierDocument)

@Serializable
enum class ImpactTransferKindDocument {
    LOCAL_BINDING,
    LOCAL_READ,
    ARGUMENT,
    RETURN,
    PROPERTY_ASSIGNMENT,
    BRANCH_ALTERNATIVE,
    WRAPPER_RETURN,
}

@Serializable
data class ImpactCompilerTransferDocument(
    val source: ImpactValueSiteReferenceDocument,
    val target: ImpactValueSiteReferenceDocument,
    val kind: ImpactTransferKindDocument,
    val evidence: ImpactTransferEvidenceDocument,
) {
    constructor(
        source: ImpactValueSiteReferenceDocument,
        target: ImpactValueSiteReferenceDocument,
        kind: ImpactTransferKindDocument,
    ) : this(source, target, kind, ImpactTransferEvidenceDocument.Direct)

    fun admitsEvidence(): Boolean =
        when (val proof = evidence) {
            ImpactTransferEvidenceDocument.Direct -> true
            is ImpactTransferEvidenceDocument.NormalBranchResult ->
                kind == ImpactTransferKindDocument.BRANCH_ALTERNATIVE &&
                    (source.role == ImpactValueRoleDocument.ExpressionResult ||
                        source.role == ImpactValueRoleDocument.LocalRead) &&
                    target.role == ImpactValueRoleDocument.ExpressionResult &&
                    source.enclosing == target.enclosing &&
                    proof.admitsAnchors(source.range, target.range) &&
                    proof.alternative.admitsIndex()
        }
}

private fun ImpactTransferEvidenceDocument.NormalBranchResult.admitsAnchors(
    source: ImpactSourceRangeDocument,
    target: ImpactSourceRangeDocument,
): Boolean =
    target == tryRange &&
        tryRange.start.value < branchRange.start.value &&
        branchRange.start.value < branchRange.end.value &&
        branchRange.end.value <= tryRange.end.value &&
        source.start.value >= branchRange.start.value &&
        source.end.value <= branchRange.end.value

private fun ImpactTryBranchAlternativeDocument.admitsIndex(): Boolean =
    when (this) {
        ImpactTryBranchAlternativeDocument.TryBody -> true
        is ImpactTryBranchAlternativeDocument.CatchBody -> index in 0 until MAX_IMPACT_CATCH_BRANCHES
    }

@Serializable
enum class ImpactBranchCompletionDocument {
    NORMAL_COMPLETION
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactTryBranchAlternativeDocument {
    @Serializable @SerialName("TRY_BODY") data object TryBody : ImpactTryBranchAlternativeDocument

    @Serializable
    @SerialName("CATCH_BODY")
    data class CatchBody(@ProtocolIntegerConstraint(minimum = 0, maximum = 1023) val index: Int) :
        ImpactTryBranchAlternativeDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactTransferEvidenceDocument {
    @Serializable @SerialName("DIRECT") data object Direct : ImpactTransferEvidenceDocument

    @Serializable
    @SerialName("NORMAL_BRANCH_RESULT")
    data class NormalBranchResult(
        @SerialName("try_range") val tryRange: ImpactSourceRangeDocument,
        @SerialName("branch_range") val branchRange: ImpactSourceRangeDocument,
        val alternative: ImpactTryBranchAlternativeDocument,
        val condition: ImpactBranchCompletionDocument,
    ) : ImpactTransferEvidenceDocument
}

@Serializable
data class ImpactRepresentationApplicationDocument(
    val source: ImpactValueSiteReferenceDocument,
    val target: ImpactValueSiteReferenceDocument,
    val invocation: ImpactInvocationReferenceDocument,
    val reference: ImpactRuleReferenceDocument,
    val rule: ImpactRepresentationRuleDocument,
)

@Serializable
data class ImpactBoundaryConnectionDocument(
    val reference: ImpactRuleReferenceDocument,
    val rule: ImpactBoundaryRuleDocument.Continuation,
    val obligations: BoundedProtocolList<ImpactBoundaryObligationDocument>,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactPathStepDocument {
    @Serializable
    @SerialName("COMPILER")
    data class Compiler(val transfer: ImpactCompilerTransferDocument) : ImpactPathStepDocument

    @Serializable
    @SerialName("MODELED_REPRESENTATION")
    data class ModeledRepresentation(val application: ImpactRepresentationApplicationDocument) : ImpactPathStepDocument

    @Serializable
    @SerialName("MODELED_BOUNDARY")
    data class ModeledBoundary(val connection: ImpactBoundaryConnectionDocument) : ImpactPathStepDocument
}

@Serializable
data class ImpactRepresentationStateDocument(
    val model: ImpactModelIdentityDocument,
    val domain: BoundedProtocolList<ImpactModelIdentifierDocument>,
    val state: ImpactModelIdentifierDocument,
)

@Serializable
enum class ImpactRepresentationUnknownDocument {
    UNMODELED_TRANSFORMATION,
    INPUT_STATE_NOT_ESTABLISHED,
    BOUNDARY_PRESERVATION_UNPROVEN,
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactRepresentationCurrentDocument {
    @Serializable
    @SerialName("KNOWN")
    data class Known(val state: ImpactRepresentationStateDocument) : ImpactRepresentationCurrentDocument

    @Serializable
    @SerialName("UNKNOWN")
    data class Unknown(val reason: ImpactRepresentationUnknownDocument) : ImpactRepresentationCurrentDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactRepresentationHistoryDocument {
    @Serializable
    @SerialName("ORIGIN")
    data class Origin(
        val reference: ImpactRuleReferenceDocument,
        val rule: ImpactRepresentationRuleDocument.Origin,
        val output: ImpactValueSiteReferenceDocument,
        val invocation: ImpactInvocationReferenceDocument,
    ) : ImpactRepresentationHistoryDocument

    @Serializable
    @SerialName("COMPILER_TRANSFER")
    data class CompilerTransfer(val transfer: ImpactCompilerTransferDocument) : ImpactRepresentationHistoryDocument

    @Serializable
    @SerialName("MODELED_APPLICATION")
    data class ModeledApplication(val application: ImpactRepresentationApplicationDocument) :
        ImpactRepresentationHistoryDocument

    @Serializable
    @SerialName("UNMODELED")
    data class Unmodeled(val source: ImpactValueSiteReferenceDocument, val target: ImpactValueSiteReferenceDocument) :
        ImpactRepresentationHistoryDocument

    @Serializable
    @SerialName("BOUNDARY_MODEL")
    data class BoundaryModel(
        val reference: ImpactRuleReferenceDocument,
        val source: ImpactBoundaryPositionDocument,
        val target: ImpactBoundaryPositionDocument,
    ) : ImpactRepresentationHistoryDocument
}

@Serializable
data class ImpactRepresentationBranchDocument(
    val current: ImpactRepresentationCurrentDocument,
    val history: BoundedProtocolList<ImpactRepresentationHistoryDocument>,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactRepresentationEvidenceDocument {
    @Serializable @SerialName("NOT_MODELED") data object NotModeled : ImpactRepresentationEvidenceDocument

    @Serializable
    @SerialName("PRESENT")
    data class Present(
        val site: ImpactValueSiteReferenceDocument,
        val branches: BoundedProtocolList<ImpactRepresentationBranchDocument>,
    ) : ImpactRepresentationEvidenceDocument
}

@Serializable
enum class ImpactConsumerOutcomeDocument {
    SATISFIED,
    DIFFERENT,
    UNKNOWN,
}

@Serializable
enum class ImpactFlowUnsupportedDocument {
    FINALLY_UNSUPPORTED,
    ABRUPT_COMPLETION,
    EXTERNAL_CALL,
    UNMODELED_CALL,
    MUTABLE_CONTROL_FLOW,
    UNSUPPORTED_EXPRESSION,
    UNRESOLVED_REFERENCE,
    UNSUPPORTED_PROPERTY,
    UNSUPPORTED_RETURN,
    WORK_LIMIT_REACHED,
    RESULT_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    NESTED_EXECUTION,
    OUTSIDE_DOMAIN,
}

@Serializable
enum class ImpactBoundaryUnresolvedDocument {
    MISSING_MODEL,
    INVALID_MODEL,
    STALE_MODEL,
    MISSING_CONSUMER,
}

@Serializable
enum class ImpactBoundaryRequiredDocument {
    REVIEWED_MODEL,
    EXACT_DOWNSTREAM_POSITION,
    CORRECTED_MODEL,
    CURRENT_BASIS_REVALIDATION,
    RETENTION_POLICY,
    DECODING_COMPATIBILITY,
    MIGRATION_PROOF,
}

@Serializable
data class ImpactBoundaryObligationDocument(
    val position: ImpactBoundaryPositionDocument,
    val required: BoundedProtocolList<ImpactBoundaryRequiredDocument>,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactPathTerminalDocument {
    @Serializable
    @SerialName("UNRESOLVED_PEER_CONTINUATION")
    data class UnresolvedPeerContinuation(
        val reference: ImpactRuleReferenceDocument,
        val target: ImpactValueSiteReferenceDocument,
        val admission: ImpactPeerSiteAdmissionDocument,
        val reason: ImpactPeerContinuationReasonDocument,
    ) : ImpactPathTerminalDocument

    @Serializable
    @SerialName("CONSUMER")
    data class Consumer(
        val site: ImpactValueSiteReferenceDocument,
        val reference: ImpactRuleReferenceDocument,
        val rule: ImpactRepresentationRuleDocument.ConsumerExpectation,
        val outcome: ImpactConsumerOutcomeDocument,
    ) : ImpactPathTerminalDocument

    @Serializable
    @SerialName("MODELED_TERMINAL")
    data class ModeledTerminal(
        val reference: ImpactRuleReferenceDocument,
        val rule: ImpactBoundaryRuleDocument.Terminal,
        val obligations: BoundedProtocolList<ImpactBoundaryObligationDocument>,
    ) : ImpactPathTerminalDocument

    @Serializable
    @SerialName("UNRESOLVED_FLOW")
    data class UnresolvedFlow(val site: ImpactValueSiteReferenceDocument, val cause: ImpactFlowUnsupportedDocument) :
        ImpactPathTerminalDocument

    @Serializable
    @SerialName("EXECUTION_STOP")
    data class ExecutionStop(val stop: ImpactExecutionStopDocument) : ImpactPathTerminalDocument

    @Serializable
    @SerialName("UNRESOLVED_READ")
    data class UnresolvedRead(val rejection: ImpactReadRejectionDocument) : ImpactPathTerminalDocument

    @Serializable
    @SerialName("UNRESOLVED_BOUNDARY")
    data class UnresolvedBoundary(
        val source: ImpactBoundaryPositionDocument,
        val reason: ImpactBoundaryUnresolvedDocument,
        val obligation: ImpactBoundaryObligationDocument,
    ) : ImpactPathTerminalDocument

    @Serializable
    @SerialName("SUPPORTED_DOMAIN_END")
    data class SupportedDomainEnd(val observation: ImpactFlowEndObservationDocument) : ImpactPathTerminalDocument

    @Serializable
    @SerialName("EXPLICIT_SCOPE_EXCLUSION")
    data class ExplicitScopeExclusion(
        val site: ImpactValueSiteReferenceDocument,
        val domain: QueryRelationDomainDocument,
        val cause: ImpactScopeExclusionDocument,
    ) : ImpactPathTerminalDocument
}

@Serializable
enum class ImpactScopeExclusionDocument {
    OUTSIDE_EXACT_FILE,
    OUTSIDE_DIRECTORY,
}
