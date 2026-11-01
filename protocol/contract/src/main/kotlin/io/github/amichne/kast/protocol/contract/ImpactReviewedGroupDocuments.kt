@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReviewedGroupCallbacksDocument {
    val selector: ImpactReviewedValueBindingDocument

    @Serializable
    @SerialName("ORIGINAL_ELEMENTS")
    data class OriginalElements(override val selector: ImpactReviewedValueBindingDocument) :
        ImpactReviewedGroupCallbacksDocument

    @Serializable
    @SerialName("TRANSFORMED_ELEMENTS")
    data class TransformedElements(
        override val selector: ImpactReviewedValueBindingDocument,
        val transform: ImpactReviewedValueBindingDocument,
    ) : ImpactReviewedGroupCallbacksDocument
}

@Serializable
enum class ImpactReviewedGroupPlacementDocument {
    ORIGINAL_ELEMENTS,
    TRANSFORMED_ELEMENTS,
    KEY_CONTENT,
}

@Serializable
@SerialName("GROUP_BUCKET")
data class ImpactReviewedGroupBucketDocument(
    val callbacks: ImpactReviewedGroupCallbacksDocument,
    val placement: ImpactReviewedGroupPlacementDocument,
    val selection: ImpactReviewedEntrySelectionDocument,
    val sourceRange: ImpactSourceRangeDocument,
    val sourceExecution: ImpactExecutionReferenceDocument,
    @ProtocolCollectionConstraint(maximumItems = 32) val callStack: BoundedProtocolList<ImpactCallFrameDocument>,
) : ImpactReviewedMapEntryOriginDocument
