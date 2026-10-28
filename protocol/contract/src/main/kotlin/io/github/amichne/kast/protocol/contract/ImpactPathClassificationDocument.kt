package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ImpactRelationshipDocument {
    EXACT,
    DERIVED,
}

@Serializable
enum class ImpactCorrespondenceFailureDocument {
    REPRESENTATION_MODEL_ONLY,
    BOUNDARY_MODEL_ONLY,
}

@Serializable
sealed interface ImpactPathClassificationDocument {
    @Serializable
    @SerialName("ESTABLISHED")
    data class Established(val relationship: ImpactRelationshipDocument) : ImpactPathClassificationDocument

    @Serializable
    @SerialName("NOT_ESTABLISHED")
    data class NotEstablished(val step: ProtocolOffset, val cause: ImpactCorrespondenceFailureDocument) :
        ImpactPathClassificationDocument
}

/** Every admitted step alternative contributes to the result. Later exact edges cannot restore lost proof. */
internal fun BoundedProtocolList<ImpactPathStepDocument>.classifyImpact(): ImpactPathClassificationDocument {
    var relationship = ImpactRelationshipDocument.EXACT
    for ((index, step) in values.withIndex()) {
        when (step) {
            is ImpactPathStepDocument.Compiler ->
                if (step.transfer.kind.relationship() == ImpactRelationshipDocument.DERIVED)
                    relationship = ImpactRelationshipDocument.DERIVED
            is ImpactPathStepDocument.ReviewedLibrary ->
                when (step.transfer.binding.semantics) {
                    ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT -> Unit
                }
            is ImpactPathStepDocument.ModeledRepresentation ->
                return notEstablished(index, ImpactCorrespondenceFailureDocument.REPRESENTATION_MODEL_ONLY)
            is ImpactPathStepDocument.ModeledBoundary ->
                return notEstablished(index, ImpactCorrespondenceFailureDocument.BOUNDARY_MODEL_ONLY)
        }
    }
    return ImpactPathClassificationDocument.Established(relationship)
}

private fun notEstablished(index: Int, cause: ImpactCorrespondenceFailureDocument): ImpactPathClassificationDocument =
    when (val position = ProtocolOffset.parse(index)) {
        is Refinement.Refined -> ImpactPathClassificationDocument.NotEstablished(position.value, cause)
        is Refinement.Rejected -> error("A bounded path has a nonnegative step index")
    }

private fun ImpactTransferKindDocument.relationship(): ImpactRelationshipDocument =
    when (this) {
        ImpactTransferKindDocument.STRING_CONTENT -> ImpactRelationshipDocument.DERIVED
        ImpactTransferKindDocument.LOCAL_BINDING,
        ImpactTransferKindDocument.LOCAL_READ,
        ImpactTransferKindDocument.ARGUMENT,
        ImpactTransferKindDocument.RETURN,
        ImpactTransferKindDocument.PROPERTY_ASSIGNMENT,
        ImpactTransferKindDocument.BRANCH_ALTERNATIVE,
        ImpactTransferKindDocument.WRAPPER_RETURN,
        ImpactTransferKindDocument.RECEIVER,
        ImpactTransferKindDocument.SOURCE_CALL_ENTRY,
        ImpactTransferKindDocument.SOURCE_CALL_RETURN,
        ImpactTransferKindDocument.FIELD_READ,
        ImpactTransferKindDocument.CONSTRUCTOR_FIELD,
        ImpactTransferKindDocument.CAST_RESULT -> ImpactRelationshipDocument.EXACT
    }
