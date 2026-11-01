@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Entry origins distinguish association correspondence from an actual mapped callback return. */
@Serializable @JsonClassDiscriminator("type") sealed interface ImpactReviewedMapEntryOriginDocument

@Serializable
enum class ImpactReviewedEntrySelectionDocument {
    COMPONENT,
    CONTENT,
}

@Serializable
@SerialName("MAPPED_RETURN")
data class ImpactReviewedMappedEntryOriginDocument(
    val family: ImpactReviewedMapEntryFamilyDocument,
    val returnRange: ImpactSourceRangeDocument,
    val returnExecution: ImpactExecutionReferenceDocument,
    @Required val selection: ImpactReviewedEntrySelectionDocument = ImpactReviewedEntrySelectionDocument.COMPONENT,
) : ImpactReviewedMapEntryOriginDocument {
    val binding
        get() = family.binding

    val callStack
        get() = family.callStack
}

@Serializable
data class ImpactReviewedMapEntryFamilyDocument(
    val binding: ImpactReviewedValueBindingDocument,
    @ProtocolCollectionConstraint(maximumItems = 32) val callStack: BoundedProtocolList<ImpactCallFrameDocument>,
)

@Serializable
@SerialName("INPUT")
data class ImpactReviewedInputEntryOriginDocument(
    val family: ImpactReviewedMapEntryFamilyDocument,
    val receiverRange: ImpactSourceRangeDocument,
    val receiverExecution: ImpactExecutionReferenceDocument,
    @Required val selection: ImpactReviewedEntrySelectionDocument = ImpactReviewedEntrySelectionDocument.COMPONENT,
) : ImpactReviewedMapEntryOriginDocument

@Serializable
@SerialName("PRESERVED_KEY")
data class ImpactReviewedPreservedKeyOriginDocument(
    val family: ImpactReviewedMapEntryFamilyDocument,
    val receiverRange: ImpactSourceRangeDocument,
    val receiverExecution: ImpactExecutionReferenceDocument,
    @Required val selection: ImpactReviewedEntrySelectionDocument = ImpactReviewedEntrySelectionDocument.COMPONENT,
) : ImpactReviewedMapEntryOriginDocument

@Serializable
@SerialName("commonMain/kotlin/collections/Maps.kt")
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
    val sections: ImpactReviewedMapSectionsDocument,
    val helpers: BoundedProtocolList<ImpactReviewedLibraryHelperDocument>,
) : ImpactReviewedArtifactsDocument, ImpactReviewedJvmArtifactHashes

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReviewedMapSectionsDocument {
    @Serializable
    @SerialName("MAP_VALUES")
    data class MapValues(
        val mapValuesLines: ImpactReviewedSourceLinesDocument,
        val mapValuesToLines: ImpactReviewedSourceLinesDocument,
    ) : ImpactReviewedMapSectionsDocument

    @Serializable
    @SerialName("ENTRY_COMPONENT")
    data class EntryComponent(val componentLines: ImpactReviewedSourceLinesDocument) : ImpactReviewedMapSectionsDocument
}
