@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReviewedCollectionSectionsDocument {
    @Serializable
    @SerialName("MAP")
    data class Map(val mapLines: ImpactReviewedSourceLinesDocument, val mapToLines: ImpactReviewedSourceLinesDocument) :
        ImpactReviewedCollectionSectionsDocument

    @Serializable
    @SerialName("SORTED_BY")
    data class SortedBy(
        val sortedByLines: ImpactReviewedSourceLinesDocument,
        val sortedWithLines: ImpactReviewedSourceLinesDocument,
        val toListLines: ImpactReviewedSourceLinesDocument,
        val toMutableListLines: ImpactReviewedSourceLinesDocument,
        val collectionToMutableListLines: ImpactReviewedSourceLinesDocument,
    ) : ImpactReviewedCollectionSectionsDocument

    @Serializable
    @SerialName("ASSOCIATE")
    data class Associate(
        val associateLines: ImpactReviewedSourceLinesDocument,
        val associateToLines: ImpactReviewedSourceLinesDocument,
    ) : ImpactReviewedCollectionSectionsDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReviewedTupleSectionsDocument {
    @Serializable
    @SerialName("TO")
    data class To(val toLines: ImpactReviewedSourceLinesDocument, val pairLines: ImpactReviewedSourceLinesDocument) :
        ImpactReviewedTupleSectionsDocument

    @Serializable
    @SerialName("PAIR")
    data class Pair(val pairLines: ImpactReviewedSourceLinesDocument) : ImpactReviewedTupleSectionsDocument
}

@Serializable
enum class ImpactReviewedLibraryHelperKindDocument {
    SORT_COMPARISON,
    SORT_ARRAY,
    SORT_MUTABLE_LIST,
    MAP_INSERTION,
    MAP_CAPACITY,
    COLLECTION_CAPACITY,
}

@Serializable
data class ImpactReviewedClassEvidenceDocument(
    val entry: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val sha256: ProtocolText,
)

@Serializable
data class ImpactReviewedLibraryHelperDocument(
    val kind: ImpactReviewedLibraryHelperKindDocument,
    val sourceEntry: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val sourceEntrySha256: ProtocolText,
    @ProtocolCollectionConstraint(maximumItems = 8)
    val sourceSections: BoundedProtocolList<ImpactReviewedSourceLinesDocument>,
    @ProtocolCollectionConstraint(maximumItems = 8)
    val classes: BoundedProtocolList<ImpactReviewedClassEvidenceDocument>,
)
