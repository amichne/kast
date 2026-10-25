package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

@Serializable
enum class ImpactReviewedValueRuleDocument {
    KOTLIN_2_4_21_ITERABLE_MAP_V1
}

@Serializable
enum class ImpactReviewedValueSemanticsDocument {
    CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT
}

@Serializable
enum class ImpactActualExecutionDocument {
    NOT_ESTABLISHED
}

@Serializable
enum class ImpactInvocationCountKnowledgeDocument {
    NOT_ESTABLISHED
}

/** The selected declaration keeps its structured compiler signature without a rendered signature string. */
@Serializable
data class ImpactReviewedFunctionDocument(
    val declaration: ImpactDeclarationReferenceDocument,
    val qualifiedIdentity: ProtocolText,
    val receiver: ProtocolText,
    val contextReceivers: BoundedProtocolList<ProtocolText>,
    val valueParameters: BoundedProtocolList<ProtocolText>,
    val typeParameterCount: QueryDiscoveryCountDocument,
    val formalIndex: ProtocolOffset,
)

@Serializable
data class ImpactReviewedArtifactsDocument(
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val binaryArtifactSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val sourceArtifactSha256: ProtocolText,
    val sourceEntry: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val sourceEntrySha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val facadeSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val implementationSha256: ProtocolText,
    val mapLines: ImpactReviewedSourceLinesDocument,
    val mapToLines: ImpactReviewedSourceLinesDocument,
)

/** Inclusive one-based lines in the artifact's exact source entry. */
@Serializable data class ImpactReviewedSourceLinesDocument(val first: ProtocolOffset, val last: ProtocolOffset)

/** Static correspondence carries no actual invocation or cardinality assertion. */
@Serializable
data class ImpactReviewedValueBindingDocument(
    val rule: ImpactReviewedValueRuleDocument,
    val basis: ImpactSemanticBasisDocument,
    val enclosing: ImpactDeclarationReferenceDocument,
    val body: ImpactCapturedBodyDocument,
    val supplierRange: ImpactSourceRangeDocument,
    val selected: ImpactReviewedFunctionDocument,
    val artifacts: ImpactReviewedArtifactsDocument,
    val semantics: ImpactReviewedValueSemanticsDocument,
    val actualExecution: ImpactActualExecutionDocument,
    val invocationCount: ImpactInvocationCountKnowledgeDocument,
)

@Serializable
data class ImpactReviewedValueTransferDocument(
    val source: ImpactValueSiteReferenceDocument,
    val target: ImpactValueSiteReferenceDocument,
    val binding: ImpactReviewedValueBindingDocument,
)
