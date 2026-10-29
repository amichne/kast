@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
enum class ImpactReviewedValueRuleDocument {
    KOTLIN_2_4_21_ITERABLE_MAP_V1,
    KOTLIN_2_3_0_ITERABLE_MAP_V1,
    KOTLIN_2_3_0_LET_V1,
    KOTLIN_2_3_0_ALSO_V1,
}

@Serializable
enum class ImpactReviewedValueSemanticsDocument {
    CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT,
    CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT,
    CONDITIONAL_RECEIVER_TO_CALL_RESULT,
    CONDITIONAL_INPUT_ELEMENT_TO_CALLBACK_PARAMETER,
    CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER,
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
@JsonClassDiscriminator("sourceEntry")
sealed interface ImpactReviewedArtifactsDocument {
    val binaryArtifactSha256: ProtocolText
    val sourceArtifactSha256: ProtocolText
    val sourceEntrySha256: ProtocolText
    val facadeSha256: ProtocolText
    val implementationSha256: ProtocolText
}

@Serializable
@SerialName("commonMain/generated/_Collections.kt")
data class ImpactReviewedMapArtifactsDocument(
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val binaryArtifactSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val sourceArtifactSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val sourceEntrySha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val facadeSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val implementationSha256: ProtocolText,
    val mapLines: ImpactReviewedSourceLinesDocument,
    val mapToLines: ImpactReviewedSourceLinesDocument,
) : ImpactReviewedArtifactsDocument

@Serializable
@SerialName("commonMain/kotlin/util/Standard.kt")
data class ImpactReviewedScopeArtifactsDocument(
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val binaryArtifactSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val sourceArtifactSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val sourceEntrySha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val facadeSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val implementationSha256: ProtocolText,
    val functionLines: ImpactReviewedSourceLinesDocument,
) : ImpactReviewedArtifactsDocument

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
    @Required
    val invocationEvidence: ImpactReviewedInvocationEvidenceDocument =
        ImpactReviewedInvocationEvidenceDocument.Unestablished,
)

/** Applied correspondence remains separate from the canonical callback-owner binding. */
@Serializable
data class ImpactReviewedValueRelationshipDocument(
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
    @Required
    val invocationEvidence: ImpactReviewedInvocationEvidenceDocument =
        ImpactReviewedInvocationEvidenceDocument.Unestablished,
) {
    companion object {
        /** Projection only. Evidence admission remains explicit at the transport reader. */
        fun fromBinding(
            binding: ImpactReviewedValueBindingDocument,
            semantics: ImpactReviewedValueSemanticsDocument,
        ): ImpactReviewedValueRelationshipDocument =
            ImpactReviewedValueRelationshipDocument(
                binding.rule,
                binding.basis,
                binding.enclosing,
                binding.body,
                binding.supplierRange,
                binding.selected,
                binding.artifacts,
                semantics,
                binding.actualExecution,
                binding.invocationCount,
                binding.invocationEvidence,
            )
    }
}

@Serializable
data class ImpactReviewedValueTransferDocument(
    val source: ImpactValueSiteReferenceDocument,
    val target: ImpactValueSiteReferenceDocument,
    val binding: ImpactReviewedValueRelationshipDocument,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReviewedInvocationEvidenceDocument {
    @Serializable @SerialName("NOT_ESTABLISHED") data object Unestablished : ImpactReviewedInvocationEvidenceDocument

    @Serializable
    @SerialName("COMPILER_DECLARED")
    data class Declared(val binding: ImpactDeclaredCallbackValueBindingDocument) :
        ImpactReviewedInvocationEvidenceDocument
}
