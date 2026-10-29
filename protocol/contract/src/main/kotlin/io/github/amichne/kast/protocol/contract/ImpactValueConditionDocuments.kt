@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** The originating occurrence without recursively embedding its preceding conditions. */
@Serializable
data class ImpactValueConditionOriginDocument(
    val execution: ImpactExecutionReferenceDocument,
    val range: ImpactSourceRangeDocument,
    val role: ImpactValueRoleDocument,
    @EncodeDefault
    @ProtocolCollectionConstraint(maximumItems = 8)
    val valuePath: BoundedProtocolList<ImpactValuePathSegmentDocument> = EmptyImpactValuePath,
    @EncodeDefault
    @ProtocolCollectionConstraint(maximumItems = 32)
    val callStack: BoundedProtocolList<ImpactCallFrameDocument> = EmptyImpactCallStack,
)

/** Conditional use-site evidence does not establish runtime reachability. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactValueConditionDocument {
    @Serializable
    @SerialName("REVIEWED_GROUP_MEMBERSHIP")
    data class ReviewedGroupMembership(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val entry: ImpactReviewedGroupBucketDocument,
    ) : ImpactValueConditionDocument

    @Serializable
    @SerialName("REVIEWED_GROUP_KEY_REPRESENTATIVE")
    data class ReviewedGroupKeyRepresentative(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val entry: ImpactReviewedGroupBucketDocument,
    ) : ImpactValueConditionDocument

    @Serializable
    @SerialName("REVIEWED_PREDICATE_ACCEPTS")
    data class ReviewedPredicateAccepts(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val binding: ImpactReviewedValueBindingDocument,
    ) : ImpactValueConditionDocument

    @Serializable
    @SerialName("REVIEWED_CALLBACK_RESULT_NON_NULL")
    data class ReviewedCallbackResultNonNull(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val binding: ImpactReviewedValueBindingDocument,
    ) : ImpactValueConditionDocument

    @Serializable
    @SerialName("REVIEWED_ENTRY_PREFIX_NORMAL")
    data class ReviewedEntryPrefixNormal(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val binding: ImpactReviewedCallValueBindingDocument,
        val entry: ImpactReviewedMapEntryOriginDocument,
        val slot: ImpactMapEntrySlotDocument,
    ) : ImpactValueConditionDocument

    @Serializable
    @SerialName("REVIEWED_PRESERVED_KEY_SURVIVES")
    data class ReviewedPreservedKeySurvives(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val entry: ImpactReviewedPreservedKeyOriginDocument,
    ) : ImpactValueConditionDocument

    @Serializable
    @SerialName("REVIEWED_ENTRY_COMPONENT_STABLE")
    data class ReviewedEntryComponentStable(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val binding: ImpactReviewedCallValueBindingDocument,
        val entry: ImpactReviewedMapEntryOriginDocument,
        val slot: ImpactMapEntrySlotDocument,
    ) : ImpactValueConditionDocument

    val origin: ImpactValueConditionOriginDocument
    val useRange: ImpactSourceRangeDocument

    @Serializable
    @SerialName("REVIEWED_MAPPED_KEY_SURVIVES")
    data class ReviewedMappedKeySurvives(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val entry: ImpactReviewedMappedEntryOriginDocument,
    ) : ImpactValueConditionDocument

    @Serializable
    @SerialName("STRING_CONTENT_NON_NULL")
    data class StringContentNonNull(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val entryRange: ImpactSourceRangeDocument,
    ) : ImpactValueConditionDocument

    @Serializable
    @SerialName("DECLARED_CALLBACK_RESULT")
    data class DeclaredCallbackResult(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val binding: ImpactDeclaredCallbackValueBindingDocument,
    ) : ImpactValueConditionDocument

    /** A premise for receiver correspondence, without a claim of actual callback completion. */
    @Serializable
    @SerialName("REVIEWED_CALLBACK_NORMAL_RETURN")
    data class ReviewedCallbackNormalReturn(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val binding: ImpactReviewedValueBindingDocument,
    ) : ImpactValueConditionDocument

    @Serializable
    @SerialName("REVIEWED_COLLECTION_NORMAL_RESULT")
    data class ReviewedCollectionNormalResult(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val binding: ImpactReviewedValueBindingDocument,
    ) : ImpactValueConditionDocument

    @Serializable
    @SerialName("REVIEWED_ENTRY_KEY_UNIQUE")
    data class ReviewedEntryKeyUnique(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val entry: ImpactReviewedMapEntryCorrelationDocument,
    ) : ImpactValueConditionDocument

    @Serializable
    @SerialName("STABLE_LOCAL")
    data class StableLocal(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val version: ImpactLocalVersionDocument.Defined,
        val originalType: ProtocolText,
        val refinedType: ProtocolText,
    ) : ImpactValueConditionDocument

    @Serializable
    @SerialName("STABLE_RECEIVER")
    data class StableReceiver(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val kind: ImpactReceiverKindDocument,
        val originalType: ProtocolText,
        val refinedType: ProtocolText,
    ) : ImpactValueConditionDocument

    /** Successful operand-preserving completion, not proof of erased runtime type arguments. */
    @Serializable
    @SerialName("CAST_SUCCESS")
    data class CastSuccess(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val kind: ImpactCastKindDocument,
        val originalType: ProtocolText,
        val targetType: ProtocolText,
    ) : ImpactValueConditionDocument
}

@Serializable
enum class ImpactCastKindDocument {
    UNSAFE,
    SAFE,
}

val EmptyImpactConditions: BoundedProtocolList<ImpactValueConditionDocument> =
    (BoundedProtocolList.create(emptyList<ImpactValueConditionDocument>()) as Refinement.Refined).value
