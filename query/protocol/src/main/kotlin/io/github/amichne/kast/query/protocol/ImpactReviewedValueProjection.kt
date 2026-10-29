package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactActualExecutionDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationCountKnowledgeDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedFunctionDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedInvocationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedScopeArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedSourceLinesDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationshipDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.relation.contract.ReviewedCallbackInvocationEvidence
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedLibraryRule
import io.github.amichne.kast.relation.contract.ReviewedSourceSection
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
    binding.impactDocument().impactMap {
        ImpactReviewedValueRelationshipDocument.fromBinding(it, semantics.impactDocument())
    }

private fun ReviewedValueSemantics.impactDocument(): ImpactReviewedValueSemanticsDocument =
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
                        semantics =
                            when (this.artifact.rule) {
                                ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1,
                                ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1 ->
                                    ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT
                                ReviewedLibraryRule.KOTLIN_2_3_0_LET_V1 ->
                                    ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT
                                ReviewedLibraryRule.KOTLIN_2_3_0_ALSO_V1 ->
                                    ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALL_RESULT
                            },
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

private fun ReviewedLibraryArtifact.impactDocument(): ImpactProjected<ImpactReviewedArtifactsDocument> =
    when (rule) {
        ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1 ->
            mapDocument(ReviewedSourceSection.MAP, ReviewedSourceSection.MAP_TO)
        ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1 ->
            mapDocument(ReviewedSourceSection.MAP_2_3_0, ReviewedSourceSection.MAP_TO_2_3_0)
        ReviewedLibraryRule.KOTLIN_2_3_0_LET_V1 -> scopeDocument(ReviewedSourceSection.LET)
        ReviewedLibraryRule.KOTLIN_2_3_0_ALSO_V1 -> scopeDocument(ReviewedSourceSection.ALSO)
    }

private fun ReviewedLibraryArtifact.mapDocument(
    map: ReviewedSourceSection,
    mapTo: ReviewedSourceSection,
): ImpactProjected<ImpactReviewedArtifactsDocument> =
    binaryArtifact.value.impactText().impactZip(sourceArtifact.value.impactText()).impactThen { (binary, source) ->
        sourceEntrySha256.impactText().impactThen { entryHash ->
            facadeSha256
                .impactText()
                .impactZip(implementationSha256.impactText())
                .impactZip(sourceLines(map))
                .impactZip(sourceLines(mapTo))
                .impactMap { (classes, mapToLines) ->
                    ImpactReviewedMapArtifactsDocument(
                        binary,
                        source,
                        entryHash,
                        classes.first.first,
                        classes.first.second,
                        classes.second,
                        mapToLines,
                    )
                }
        }
    }

private fun ReviewedLibraryArtifact.scopeDocument(
    section: ReviewedSourceSection
): ImpactProjected<ImpactReviewedArtifactsDocument> =
    binaryArtifact.value.impactText().impactZip(sourceArtifact.value.impactText()).impactThen { (binary, source) ->
        sourceEntrySha256
            .impactText()
            .impactZip(facadeSha256.impactText())
            .impactZip(implementationSha256.impactText())
            .impactThen { (hashes, implementation) ->
                sourceLines(section).impactMap { lines ->
                    ImpactReviewedScopeArtifactsDocument(
                        binary,
                        source,
                        hashes.first,
                        hashes.second,
                        implementation,
                        lines,
                    )
                }
            }
    }

private fun ReviewedLibraryRule.impactDocument(): ImpactReviewedValueRuleDocument =
    when (this) {
        ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_V1
        ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1 ->
            ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1
        ReviewedLibraryRule.KOTLIN_2_3_0_LET_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1
        ReviewedLibraryRule.KOTLIN_2_3_0_ALSO_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1
    }

private fun ReviewedCallbackInvocationEvidence.impactDocument():
    ImpactProjected<ImpactReviewedInvocationEvidenceDocument> =
    when (this) {
        ReviewedCallbackInvocationEvidence.Unestablished ->
            Refinement.Refined(ImpactReviewedInvocationEvidenceDocument.Unestablished)
        is ReviewedCallbackInvocationEvidence.Declared ->
            binding.impactDocument().impactMap { ImpactReviewedInvocationEvidenceDocument.Declared(it) }
    }

private fun ReviewedLibraryArtifact.sourceLines(
    section: ReviewedSourceSection
): ImpactProjected<ImpactReviewedSourceLinesDocument> =
    when (val source = sourceSections.singleOrNull { it == section }) {
        null -> Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
        else ->
            source.firstLine.impactOffset().impactZip(source.lastLine.impactOffset()).impactMap { (start, end) ->
                ImpactReviewedSourceLinesDocument(start, end)
            }
    }
