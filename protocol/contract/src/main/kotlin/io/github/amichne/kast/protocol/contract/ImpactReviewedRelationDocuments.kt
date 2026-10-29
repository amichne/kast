@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Callback ownership and ordinary selected calls are separate sources of reviewed authority. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReviewedValueAuthorityDocument {
    val rule: ImpactReviewedValueRuleDocument
    val basis: ImpactSemanticBasisDocument
    val enclosing: ImpactDeclarationReferenceDocument
    val supplierRange: ImpactSourceRangeDocument
    val artifacts: ImpactReviewedArtifactsDocument
}

@Serializable
@SerialName("CALL")
data class ImpactReviewedCallValueBindingDocument(
    override val rule: ImpactReviewedValueRuleDocument,
    override val basis: ImpactSemanticBasisDocument,
    override val enclosing: ImpactDeclarationReferenceDocument,
    val invocation: ImpactInvocationReferenceDocument,
    val selected: ImpactReviewedOrdinaryTargetDocument,
    override val artifacts: ImpactReviewedArtifactsDocument,
    @Required val entryReadOrder: ImpactReviewedEntryReadOrderDocument = ImpactReviewedEntryReadOrderDocument.Direct,
) : ImpactReviewedValueAuthorityDocument {
    override val supplierRange: ImpactSourceRangeDocument
        get() = invocation.range
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReviewedOrdinaryTargetDocument {
    val declaration: ImpactDeclarationReferenceDocument

    @Serializable
    @SerialName("FUNCTION")
    data class Function(val signature: ImpactDeclaredCallbackTargetDocument) : ImpactReviewedOrdinaryTargetDocument {
        override val declaration: ImpactDeclarationReferenceDocument
            get() = signature.declaration
    }

    @Serializable
    @SerialName("PROPERTY")
    data class Property(
        override val declaration: ImpactDeclarationReferenceDocument,
        val qualifiedIdentity: ProtocolText,
        val receiver: ImpactDeclaredCallbackReceiverDocument,
        val contextReceivers: BoundedProtocolList<ProtocolText>,
        val returnType: ProtocolText,
    ) : ImpactReviewedOrdinaryTargetDocument
}

@Serializable
enum class ImpactPairSlotDocument {
    FIRST,
    SECOND,
}

@Serializable
enum class ImpactMapEntrySlotDocument {
    KEY,
    VALUE,
}

@Serializable
enum class ImpactReviewedTupleInputDocument {
    EXTENSION_RECEIVER,
    ARGUMENT_ZERO,
    ARGUMENT_ONE,
}

@Serializable
enum class ImpactReviewedCollectionSelectionDocument {
    ELEMENT,
    CONTENT,
}

/** Construction provenance has no recursive source site or preceding condition collection. */
@Serializable
data class ImpactReviewedTupleOriginDocument(
    val binding: ImpactReviewedCallValueBindingDocument,
    @ProtocolCollectionConstraint(maximumItems = 32) val callStack: BoundedProtocolList<ImpactCallFrameDocument>,
)

/** One symbolic returned-pair family, without a runtime iteration or distinct-key assertion. */
@Serializable
@SerialName("ASSOCIATION")
data class ImpactReviewedMapEntryCorrelationDocument(
    val binding: ImpactReviewedValueBindingDocument,
    val tupleOrigin: ImpactReviewedTupleOriginDocument,
    val returnRange: ImpactSourceRangeDocument,
    val returnExecution: ImpactExecutionReferenceDocument,
    @ProtocolCollectionConstraint(maximumItems = 32) val callStack: BoundedProtocolList<ImpactCallFrameDocument>,
    @EncodeDefault
    val keyEvidence: ImpactReviewedEntryKeyEvidenceDocument = ImpactReviewedEntryKeyEvidenceDocument.Conditional,
) : ImpactReviewedMapEntryOriginDocument

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReviewedValueRelationDocument {
    @Serializable
    @SerialName("CALLBACK_RECEIVER_INPUT")
    data class CallbackReceiverInput(val receiver: ImpactReviewedCallbackReceiverDocument) :
        ImpactReviewedValueRelationDocument

    @Serializable
    @SerialName("GROUP_PLACEMENT")
    data class GroupPlacement(val entry: ImpactReviewedGroupBucketDocument) : ImpactReviewedValueRelationDocument

    @Serializable
    @SerialName("FILTERED_ELEMENTS")
    data class FilteredElements(val selection: ImpactReviewedCollectionSelectionDocument) :
        ImpactReviewedValueRelationDocument

    @Serializable
    @SerialName("NON_NULL_CALLBACK_RESULT")
    data object NonNullCallbackResult : ImpactReviewedValueRelationDocument

    @Serializable
    @SerialName("ENTRY_INPUT")
    data class EntryInput(val entry: ImpactReviewedInputEntryOriginDocument, val slot: ImpactMapEntrySlotDocument) :
        ImpactReviewedValueRelationDocument

    @Serializable
    @SerialName("PRESERVED_KEY")
    data class PreservedKey(val entry: ImpactReviewedPreservedKeyOriginDocument) : ImpactReviewedValueRelationDocument

    @Serializable
    @SerialName("ENTRY_PROJECTION")
    data class EntryProjection(val entry: ImpactReviewedMapEntryOriginDocument, val slot: ImpactMapEntrySlotDocument) :
        ImpactReviewedValueRelationDocument

    @Serializable
    @SerialName("MAPPED_VALUE")
    data class MappedValue(val entry: ImpactReviewedMappedEntryOriginDocument) : ImpactReviewedValueRelationDocument

    @Serializable
    @SerialName("CALLBACK_CORRESPONDENCE")
    data class CallbackCorrespondence(val semantics: ImpactReviewedValueSemanticsDocument) :
        ImpactReviewedValueRelationDocument

    @Serializable
    @SerialName("REORDERED_ELEMENTS")
    data class ReorderedElements(val selection: ImpactReviewedCollectionSelectionDocument) :
        ImpactReviewedValueRelationDocument

    @Serializable
    @SerialName("TUPLE_CONSTRUCTION")
    data class TupleConstruction(
        val origin: ImpactReviewedTupleOriginDocument,
        val input: ImpactReviewedTupleInputDocument,
        val slot: ImpactPairSlotDocument,
    ) : ImpactReviewedValueRelationDocument

    @Serializable
    @SerialName("TUPLE_PROJECTION")
    data class TupleProjection(val origin: ImpactReviewedTupleOriginDocument, val slot: ImpactPairSlotDocument) :
        ImpactReviewedValueRelationDocument

    @Serializable
    @SerialName("ASSOCIATION_COMPONENT")
    data class AssociationComponent(
        val entry: ImpactReviewedMapEntryCorrelationDocument,
        val slot: ImpactMapEntrySlotDocument,
    ) : ImpactReviewedValueRelationDocument
}

@Serializable
data class ImpactReviewedValueRelationshipDocument(
    val authority: ImpactReviewedValueAuthorityDocument,
    val relation: ImpactReviewedValueRelationDocument,
) {
    val basis: ImpactSemanticBasisDocument
        get() = authority.basis

    val enclosing: ImpactDeclarationReferenceDocument
        get() = authority.enclosing

    companion object {
        fun fromBinding(
            binding: ImpactReviewedValueBindingDocument,
            semantics: ImpactReviewedValueSemanticsDocument,
        ): ImpactReviewedValueRelationshipDocument =
            ImpactReviewedValueRelationshipDocument(
                binding,
                ImpactReviewedValueRelationDocument.CallbackCorrespondence(semantics),
            )
    }
}
