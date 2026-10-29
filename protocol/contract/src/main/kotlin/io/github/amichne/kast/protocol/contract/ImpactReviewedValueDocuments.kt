@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
enum class ImpactReviewedValueRuleDocument {
    KOTLIN_2_3_0_RUN_TOP_LEVEL_V1,
    KOTLIN_2_3_0_RUN_RECEIVER_V1,
    KOTLIN_2_3_0_WITH_V1,
    KOTLIN_2_3_0_APPLY_V1,
    KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1,
    KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1,
    KOTLIN_2_3_0_ITERABLE_FILTER_V1,
    KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1,
    KOTLIN_2_3_0_MAP_ENTRY_KEY_V1,
    KOTLIN_2_3_0_MAP_ENTRY_VALUE_V1,
    KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1,
    KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1,
    KOTLIN_2_3_0_MAP_VALUES_V1,
    KOTLIN_2_4_21_ITERABLE_MAP_V1,
    KOTLIN_2_3_0_ITERABLE_MAP_V1,
    KOTLIN_2_3_0_LET_V1,
    KOTLIN_2_3_0_ALSO_V1,
    KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1,
    KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1,
    KOTLIN_2_3_0_TO_V1,
    KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1,
    KOTLIN_2_3_0_PAIR_FIRST_V1,
    KOTLIN_2_3_0_PAIR_SECOND_V1,
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
    val receiver: ImpactDeclaredCallbackReceiverDocument,
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
}

@Serializable
@SerialName("commonMain/generated/_Collections.kt")
data class ImpactReviewedCollectionArtifactsDocument(
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
    val sections: ImpactReviewedCollectionSectionsDocument,
    val helpers: BoundedProtocolList<ImpactReviewedLibraryHelperDocument>,
) : ImpactReviewedArtifactsDocument, ImpactReviewedJvmArtifactHashes

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
) : ImpactReviewedArtifactsDocument, ImpactReviewedJvmArtifactHashes

@Serializable
@SerialName("commonMain/kotlin/util/Tuples.kt")
data class ImpactReviewedTupleArtifactsDocument(
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
    val sections: ImpactReviewedTupleSectionsDocument,
) : ImpactReviewedArtifactsDocument, ImpactReviewedJvmArtifactHashes

/** Inclusive one-based lines in the artifact's exact source entry. */
@Serializable data class ImpactReviewedSourceLinesDocument(val first: ProtocolOffset, val last: ProtocolOffset)

/** Static correspondence carries no actual invocation or cardinality assertion. */
@Serializable
@SerialName("CALLBACK")
data class ImpactReviewedValueBindingDocument(
    override val rule: ImpactReviewedValueRuleDocument,
    override val basis: ImpactSemanticBasisDocument,
    override val enclosing: ImpactDeclarationReferenceDocument,
    val body: ImpactCapturedBodyDocument,
    override val supplierRange: ImpactSourceRangeDocument,
    val selected: ImpactReviewedFunctionDocument,
    override val artifacts: ImpactReviewedArtifactsDocument,
    val actualExecution: ImpactActualExecutionDocument,
    val invocationCount: ImpactInvocationCountKnowledgeDocument,
    @Required
    val invocationEvidence: ImpactReviewedInvocationEvidenceDocument =
        ImpactReviewedInvocationEvidenceDocument.Unestablished,
    @Required val parent: ImpactReviewedCallbackParentDocument = ImpactReviewedCallbackParentDocument.Declaration,
) : ImpactReviewedValueAuthorityDocument

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
