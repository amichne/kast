package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactActualExecutionDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationCountKnowledgeDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedFunctionDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedInvocationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationshipDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.relation.contract.ReviewedCallbackInvocationEvidence
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedLibraryRule
import io.github.amichne.kast.relation.contract.ReviewedValueSemantics
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueDeclarationIdentity
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

internal fun ReviewedValueTransfer.impactDocument(): ImpactProjected<ImpactReviewedValueTransferDocument> =
    source.impactDocument().impactZip(target.impactDocument()).impactZip(relationshipDocument()).impactMap {
        (sites, binding) ->
        ImpactReviewedValueTransferDocument(sites.first, sites.second, binding)
    }

internal fun ReviewedValueTransfer.relationshipDocument(): ImpactProjected<ImpactReviewedValueRelationshipDocument> =
    binding.impactDocument().impactZip(relation.impactDocument()).impactMap { (authority, relation) ->
        ImpactReviewedValueRelationshipDocument(authority, relation)
    }

internal fun ReviewedValueSemantics.impactDocument(): ImpactReviewedValueSemanticsDocument =
    when (this) {
        ReviewedValueSemantics.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT ->
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT
        ReviewedValueSemantics.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT ->
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT
        ReviewedValueSemantics.CONDITIONAL_RECEIVER_TO_CALL_RESULT ->
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALL_RESULT
        ReviewedValueSemantics.CONDITIONAL_INPUT_ELEMENT_TO_CALLBACK_PARAMETER ->
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_INPUT_ELEMENT_TO_CALLBACK_PARAMETER
        ReviewedValueSemantics.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER ->
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER
    }

internal fun ReviewedCallbackValueBinding.impactDocument(): ImpactProjected<ImpactReviewedValueBindingDocument> =
    enclosing.impactDeclaration().impactThen { host ->
        body
            .impactCaptureBody()
            .impactZip(occurrence.range.impactDocument())
            .impactZip(selected.impactDocument(basis))
            .impactZip(artifact.impactDocument())
            .impactThen { (supply, artifact) ->
                invocationEvidence.impactDocument().impactMap { invocation ->
                    ImpactReviewedValueBindingDocument(
                        rule = this.artifact.rule.impactDocument(),
                        basis = host.basis,
                        enclosing = host,
                        body = supply.first.first,
                        supplierRange = supply.first.second,
                        selected = supply.second,
                        artifacts = artifact,
                        actualExecution = ImpactActualExecutionDocument.NOT_ESTABLISHED,
                        invocationCount = ImpactInvocationCountKnowledgeDocument.NOT_ESTABLISHED,
                        invocationEvidence = invocation,
                    )
                }
            }
    }

private fun ReviewedLibraryCallable.impactDocument(
    basis: SemanticReadIdentity
): ImpactProjected<ImpactReviewedFunctionDocument> {
    val signature =
        target.signature as? CanonicalCompilerSignature.Function
            ?: return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    val receiver =
        signature.receiver as? CanonicalCompilerReceiver.Present
            ?: return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    return ValueDeclarationIdentity.fromCompiler(target).impactDocument(basis).impactThen { declaration ->
        signature.qualifiedIdentity.value.impactText().impactZip(receiver.type.value.impactText()).impactThen {
            (name, receiver) ->
            signature.contextReceivers
                .impactEach { it.value.impactText() }
                .impactZip(signature.valueParameters.impactEach { it.value.impactText() })
                .impactZip(
                    QueryDiscoveryCountDocument.parse(signature.typeParameterCount.value.toLong())
                        .impactFailure(ImpactPathProjectionFailure::Count)
                )
                .impactZip(position.value.impactOffset())
                .impactMap { (types, position) ->
                    ImpactReviewedFunctionDocument(
                        declaration,
                        name,
                        receiver,
                        types.first.first,
                        types.first.second,
                        types.second,
                        position,
                    )
                }
        }
    }
}

internal fun ReviewedLibraryRule.impactDocument(): ImpactReviewedValueRuleDocument =
    when (this) {
        ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_V1
        ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1 ->
            ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1
        ReviewedLibraryRule.KOTLIN_2_3_0_LET_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1
        ReviewedLibraryRule.KOTLIN_2_3_0_ALSO_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1
        ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1 ->
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1
        ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1 ->
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1
        ReviewedLibraryRule.KOTLIN_2_3_0_TO_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_TO_V1
        ReviewedLibraryRule.KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1 ->
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1
        ReviewedLibraryRule.KOTLIN_2_3_0_PAIR_FIRST_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_FIRST_V1
        ReviewedLibraryRule.KOTLIN_2_3_0_PAIR_SECOND_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_SECOND_V1
    }

private fun ReviewedCallbackInvocationEvidence.impactDocument():
    ImpactProjected<ImpactReviewedInvocationEvidenceDocument> =
    when (this) {
        ReviewedCallbackInvocationEvidence.Unestablished ->
            Refinement.Refined(ImpactReviewedInvocationEvidenceDocument.Unestablished)
        is ReviewedCallbackInvocationEvidence.Declared ->
            binding.impactDocument().impactMap { ImpactReviewedInvocationEvidenceDocument.Declared(it) }
    }
