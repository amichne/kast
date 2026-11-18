package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactPrimitiveArtifactDocument
import io.github.amichne.kast.protocol.contract.ImpactPrimitiveSourceEvidenceDocument
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
import io.github.amichne.kast.relation.contract.ReviewedPrimitiveArtifact
import io.github.amichne.kast.relation.contract.ReviewedPrimitiveFamily
import io.github.amichne.kast.relation.contract.ReviewedPrimitiveRule
import io.github.amichne.kast.relation.contract.ReviewedRuleEvidence

internal fun ReviewedRuleEvidence.impactDocument(): ImpactReviewedValueRuleDocument =
    when (this) {
        ReviewedPrimitiveRule.KOTLIN_2_3_0_INT_PLUS_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_PLUS_V1
        ReviewedPrimitiveRule.KOTLIN_2_3_0_INT_TIMES_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_TIMES_V1
        ReviewedPrimitiveRule.KOTLIN_2_3_0_INT_MIN_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_MIN_V1
        ReviewedPrimitiveRule.KOTLIN_2_3_0_INT_MAX_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_MAX_V1
        ReviewedPrimitiveRule.KOTLIN_2_3_0_INT_MINUS_V1 -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_MINUS_V1
        is ReviewedLibraryRule -> impactDocument()
        ReviewedEntryDeclarationRule.KOTLIN_2_3_0_MAP_ENTRY_KEY_V1 ->
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_KEY_V1
        ReviewedEntryDeclarationRule.KOTLIN_2_3_0_MAP_ENTRY_VALUE_V1 ->
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_VALUE_V1
    }

internal fun ReviewedArtifactEvidence.impactDocument(): ImpactProjected<ImpactReviewedArtifactsDocument> =
    when (this) {
        is ReviewedPrimitiveArtifact ->
            binaryArtifact.value
                .impactText()
                .impactZip(sourceArtifact.value.impactText())
                .impactZip(sourceEntrySha256.impactText())
                .impactMap { (archives, entry) ->
                    ImpactPrimitiveArtifactDocument(
                        sourceEvidence =
                            when (rule.family) {
                                ReviewedPrimitiveFamily.MEMBER ->
                                    ImpactPrimitiveSourceEvidenceDocument.REVIEWED_REFERENCE_FOR_BUILTIN_METADATA
                                ReviewedPrimitiveFamily.MATH ->
                                    ImpactPrimitiveSourceEvidenceDocument.MATCHED_LIBRARY_SOURCE_ARCHIVE
                            },
                        binaryArtifactSha256 = archives.first,
                        sourceArtifactSha256 = archives.second,
                        sourceEntrySha256 = entry,
                    )
                }
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
