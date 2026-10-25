package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactActualExecutionDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationCountKnowledgeDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedFunctionDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedSourceLinesDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedIterableMapArtifact
import io.github.amichne.kast.relation.contract.ReviewedIterableMapCallable
import io.github.amichne.kast.relation.contract.ReviewedMapSourceSection
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueDeclarationIdentity
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

internal fun ReviewedValueTransfer.impactDocument(): ImpactProjected<ImpactReviewedValueTransferDocument> =
    source.impactDocument().impactZip(target.impactDocument()).impactZip(binding.impactDocument()).impactMap {
        (sites, binding) ->
        ImpactReviewedValueTransferDocument(sites.first, sites.second, binding)
    }

internal fun ReviewedCallbackValueBinding.impactDocument(): ImpactProjected<ImpactReviewedValueBindingDocument> =
    enclosing.impactDeclaration().impactThen { host ->
        body
            .impactCaptureBody()
            .impactZip(occurrence.range.impactDocument())
            .impactZip(selected.impactDocument(basis))
            .impactZip(artifact.impactDocument())
            .impactMap { (supply, artifact) ->
                ImpactReviewedValueBindingDocument(
                    rule = ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1,
                    basis = host.basis,
                    enclosing = host,
                    body = supply.first.first,
                    supplierRange = supply.first.second,
                    selected = supply.second,
                    artifacts = artifact,
                    semantics = ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT,
                    actualExecution = ImpactActualExecutionDocument.NOT_ESTABLISHED,
                    invocationCount = ImpactInvocationCountKnowledgeDocument.NOT_ESTABLISHED,
                )
            }
    }

private fun ReviewedIterableMapCallable.impactDocument(
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

private fun ReviewedIterableMapArtifact.impactDocument(): ImpactProjected<ImpactReviewedArtifactsDocument> =
    binaryArtifact.value.impactText().impactZip(sourceArtifact.value.impactText()).impactThen { (binary, source) ->
        sourceEntry.impactText().impactZip(sourceEntrySha256.impactText()).impactThen { (entry, entryHash) ->
            facadeSha256
                .impactText()
                .impactZip(implementationSha256.impactText())
                .impactZip(sourceLines(ReviewedMapSourceSection.MAP))
                .impactZip(sourceLines(ReviewedMapSourceSection.MAP_TO))
                .impactMap { (classes, mapToLines) ->
                    ImpactReviewedArtifactsDocument(
                        binary,
                        source,
                        entry,
                        entryHash,
                        classes.first.first,
                        classes.first.second,
                        classes.second,
                        mapToLines,
                    )
                }
        }
    }

private fun ReviewedIterableMapArtifact.sourceLines(
    section: ReviewedMapSourceSection
): ImpactProjected<ImpactReviewedSourceLinesDocument> =
    when (val source = sourceSections.singleOrNull { it == section }) {
        null -> Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
        else ->
            source.firstLine.impactOffset().impactZip(source.lastLine.impactOffset()).impactMap { (start, end) ->
                ImpactReviewedSourceLinesDocument(start, end)
            }
    }
