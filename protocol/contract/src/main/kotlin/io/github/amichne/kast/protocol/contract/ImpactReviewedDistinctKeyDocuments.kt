@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Compiler-selected vararg evidence remains separate from reviewed callback authority. */
@Serializable
data class ImpactReviewedVarargFormalDocument(
    val enclosing: ImpactDeclarationReferenceDocument,
    val invocation: ImpactInvocationReferenceDocument,
    val target: ImpactDeclaredCallbackTargetDocument,
    val position: ProtocolOffset,
)

/** String constants keep empty values, whitespace, and exact code units. */
@Serializable @JvmInline value class ImpactReviewedStringConstantDocument(val value: String)

@Serializable
data class ImpactReviewedStringVarargArgumentDocument(
    val formal: ImpactReviewedVarargFormalDocument,
    val ordinal: ProtocolOffset,
    val range: ImpactSourceRangeDocument,
    val value: ImpactReviewedStringConstantDocument,
)

@Serializable
enum class ImpactReviewedStringListRuleDocument {
    KOTLIN_2_3_0_DIRECT_VARARG_STRINGS_V1
}

@Serializable
data class ImpactReviewedStringListSectionDocument(
    val sourceEntry: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val sourceEntrySha256: ProtocolText,
    val lines: ImpactReviewedSourceLinesDocument,
)

@Serializable
data class ImpactReviewedStringListArtifactDocument(
    val rule: ImpactReviewedStringListRuleDocument,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val binaryArtifactSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val sourceArtifactSha256: ProtocolText,
    @ProtocolCollectionConstraint(maximumItems = 3)
    val sourceSections: BoundedProtocolList<ImpactReviewedStringListSectionDocument>,
    @ProtocolCollectionConstraint(maximumItems = 5)
    val classes: BoundedProtocolList<ImpactReviewedClassEvidenceDocument>,
)

@Serializable
data class ImpactReviewedStringListInputDocument(
    val formal: ImpactReviewedVarargFormalDocument,
    val artifact: ImpactReviewedStringListArtifactDocument,
    val actualArgumentCount: QueryDiscoveryCountDocument,
    val actualArgumentRanges: BoundedProtocolList<ImpactSourceRangeDocument>,
    val arguments: BoundedProtocolList<ImpactReviewedStringVarargArgumentDocument>,
)

@Serializable
data class ImpactReviewedDirectFormalKeyDocument(
    val binding: ImpactReviewedValueBindingDocument,
    val formal: ImpactFormalParameterDocument,
    val tupleOrigin: ImpactReviewedTupleOriginDocument,
    val keyRange: ImpactSourceRangeDocument,
    val returnRange: ImpactSourceRangeDocument,
)

@Serializable
data class ImpactReviewedDistinctStringKeysDocument(
    val input: ImpactReviewedStringListInputDocument,
    val key: ImpactReviewedDirectFormalKeyDocument,
    val associationInvocation: ImpactInvocationReferenceDocument,
    val receiverRange: ImpactSourceRangeDocument,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReviewedEntryKeyEvidenceDocument {
    @Serializable @SerialName("CONDITIONAL") data object Conditional : ImpactReviewedEntryKeyEvidenceDocument

    @Serializable
    @SerialName("DISTINCT")
    data class Distinct(val proof: ImpactReviewedDistinctStringKeysDocument) : ImpactReviewedEntryKeyEvidenceDocument
}
