package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactReviewedArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryDeclarationArtifactDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMetadataEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedSourceLinesDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument
import io.github.amichne.kast.relation.contract.ReviewedArtifactEvidence
import io.github.amichne.kast.relation.contract.ReviewedEntryDeclarationArtifact
import io.github.amichne.kast.relation.contract.ReviewedEntryDeclarationRule
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedLibraryRule
import io.github.amichne.kast.relation.contract.ReviewedRuleEvidence

internal fun ReviewedRuleEvidence.impactDocument(): ImpactReviewedValueRuleDocument =
    when (this) {
        is ReviewedLibraryRule -> impactDocument()
        ReviewedEntryDeclarationRule.KOTLIN_2_3_0_MAP_ENTRY_KEY_V1 ->
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_KEY_V1
        ReviewedEntryDeclarationRule.KOTLIN_2_3_0_MAP_ENTRY_VALUE_V1 ->
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_VALUE_V1
    }

internal fun ReviewedArtifactEvidence.impactDocument(): ImpactProjected<ImpactReviewedArtifactsDocument> =
    when (this) {
        is ReviewedLibraryArtifact -> impactDocument()
        is ReviewedEntryDeclarationArtifact -> entryDocument()
    }

private fun ReviewedEntryDeclarationArtifact.entryDocument():
    ImpactProjected<ImpactReviewedEntryDeclarationArtifactDocument> =
    binaryArtifact.value
        .impactText()
        .impactZip(sourceArtifact.value.impactText())
        .impactZip(sourceEntrySha256.impactText())
        .impactZip(
            sourceSections.single().let { section ->
                section.firstLine.impactOffset().impactZip(section.lastLine.impactOffset()).impactMap { (first, last) ->
                    ImpactReviewedSourceLinesDocument(first, last)
                }
            }
        )
        .impactZip(metadata.entry.impactText().impactZip(metadata.sha256.impactText()))
        .impactMap { (source, metadata) ->
            ImpactReviewedEntryDeclarationArtifactDocument(
                source.first.first.first,
                source.first.first.second,
                source.first.second,
                source.second,
                ImpactReviewedMetadataEvidenceDocument(metadata.first, metadata.second),
            )
        }
