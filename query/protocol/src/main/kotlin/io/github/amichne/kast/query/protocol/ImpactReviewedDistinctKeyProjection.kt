package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactReviewedClassEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedDirectFormalKeyDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedDistinctStringKeysDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryKeyEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedSourceLinesDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedStringConstantDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedStringListArtifactDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedStringListInputDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedStringListRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedStringListSectionDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedStringVarargArgumentDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedVarargFormalDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.relation.contract.ReviewedDirectFormalKey
import io.github.amichne.kast.relation.contract.ReviewedDistinctStringKeys
import io.github.amichne.kast.relation.contract.ReviewedEntryKeyEvidence
import io.github.amichne.kast.relation.contract.ReviewedStringListArtifact
import io.github.amichne.kast.relation.contract.ReviewedStringListInput
import io.github.amichne.kast.relation.contract.ReviewedStringListRule
import io.github.amichne.kast.relation.contract.ReviewedStringListSourceSection
import io.github.amichne.kast.relation.contract.ReviewedStringVarargArgument
import io.github.amichne.kast.relation.contract.ReviewedVarargFormal

internal fun ReviewedEntryKeyEvidence.impactDocument(): ImpactProjected<ImpactReviewedEntryKeyEvidenceDocument> =
    when (this) {
        ReviewedEntryKeyEvidence.Conditional -> Refinement.Refined(ImpactReviewedEntryKeyEvidenceDocument.Conditional)
        is ReviewedEntryKeyEvidence.Distinct ->
            proof.impactDocument().impactMap(ImpactReviewedEntryKeyEvidenceDocument::Distinct)
    }

private fun ReviewedDistinctStringKeys.impactDocument(): ImpactProjected<ImpactReviewedDistinctStringKeysDocument> =
    input
        .impactDocument()
        .impactZip(key.impactDocument())
        .impactZip(associationInvocation.impactDocument())
        .impactZip(receiverRange.impactDocument())
        .impactMap { (call, range) ->
            ImpactReviewedDistinctStringKeysDocument(call.first.first, call.first.second, call.second, range)
        }

private fun ReviewedDirectFormalKey.impactDocument(): ImpactProjected<ImpactReviewedDirectFormalKeyDocument> =
    binding
        .impactDocument()
        .impactZip(formal.impactDocument())
        .impactZip(tupleOrigin.impactDocument())
        .impactZip(keyRange.impactDocument())
        .impactZip(returnRange.impactDocument())
        .impactMap { (key, returned) ->
            ImpactReviewedDirectFormalKeyDocument(
                key.first.first.first,
                key.first.first.second,
                key.first.second,
                key.second,
                returned,
            )
        }

private fun ReviewedStringListInput.impactDocument(): ImpactProjected<ImpactReviewedStringListInputDocument> =
    formal
        .impactDocument()
        .impactZip(artifact.impactDocument())
        .impactZip(
            QueryDiscoveryCountDocument.parse(actualArgumentCount.toLong())
                .impactFailure(ImpactPathProjectionFailure::Count)
        )
        .impactZip(actualArgumentRanges.impactEach { it.impactDocument() })
        .impactZip(arguments.impactEach { it.impactDocument() })
        .impactMap { (input, arguments) ->
            ImpactReviewedStringListInputDocument(
                input.first.first.first,
                input.first.first.second,
                input.first.second,
                input.second,
                arguments,
            )
        }

private fun ReviewedVarargFormal.impactDocument(): ImpactProjected<ImpactReviewedVarargFormalDocument> =
    invocation.enclosing
        .impactDeclaration()
        .impactZip(invocation.impactDocument())
        .impactZip(target.impactCallableTarget(invocation.basis))
        .impactZip(position.value.impactOffset())
        .impactMap { (selected, position) ->
            ImpactReviewedVarargFormalDocument(selected.first.first, selected.first.second, selected.second, position)
        }

private fun ReviewedStringVarargArgument.impactDocument(): ImpactProjected<ImpactReviewedStringVarargArgumentDocument> =
    formal.impactDocument().impactZip(ordinal.value.impactOffset()).impactZip(range.impactDocument()).impactMap {
        (argument, range) ->
        ImpactReviewedStringVarargArgumentDocument(
            argument.first,
            argument.second,
            range,
            ImpactReviewedStringConstantDocument(value),
        )
    }

private fun ReviewedStringListArtifact.impactDocument(): ImpactProjected<ImpactReviewedStringListArtifactDocument> =
    binary.value
        .impactText()
        .impactZip(sources.value.impactText())
        .impactZip(sourceSections.impactEach { it.impactDocument() })
        .impactZip(
            classes.impactEach { evidence ->
                evidence.entry.impactText().impactZip(evidence.sha256.impactText()).impactMap { (entry, hash) ->
                    ImpactReviewedClassEvidenceDocument(entry, hash)
                }
            }
        )
        .impactMap { (source, classes) ->
            ImpactReviewedStringListArtifactDocument(
                when (rule) {
                    ReviewedStringListRule.KOTLIN_2_3_0_DIRECT_VARARG_STRINGS_V1 ->
                        ImpactReviewedStringListRuleDocument.KOTLIN_2_3_0_DIRECT_VARARG_STRINGS_V1
                },
                source.first.first,
                source.first.second,
                source.second,
                classes,
            )
        }

private fun ReviewedStringListSourceSection.impactDocument(): ImpactProjected<ImpactReviewedStringListSectionDocument> =
    sourceEntry
        .impactText()
        .impactZip(sourceEntrySha256.impactText())
        .impactZip(firstLine.impactOffset())
        .impactZip(lastLine.impactOffset())
        .impactMap { (source, last) ->
            ImpactReviewedStringListSectionDocument(
                source.first.first,
                source.first.second,
                ImpactReviewedSourceLinesDocument(source.second, last),
            )
        }
