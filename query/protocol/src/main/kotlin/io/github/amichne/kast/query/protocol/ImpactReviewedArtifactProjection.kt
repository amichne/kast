package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactReviewedArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedClassEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCollectionArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCollectionSectionsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedLibraryHelperDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedLibraryHelperKindDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedScopeArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedSourceLinesDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedTupleArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedTupleSectionsDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedLibraryHelper
import io.github.amichne.kast.relation.contract.ReviewedLibraryRule
import io.github.amichne.kast.relation.contract.ReviewedSourceSection

private data class ArtifactHashes(
    val binary: ProtocolText,
    val source: ProtocolText,
    val entry: ProtocolText,
    val facade: ProtocolText,
    val implementation: ProtocolText,
)

internal fun ReviewedLibraryArtifact.impactDocument(): ImpactProjected<ImpactReviewedArtifactsDocument> =
    hashes().impactThen { hashes ->
        when (rule) {
            ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1,
            ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1,
            ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1,
            ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1 ->
                collectionSections().impactZip(helpers.impactEach { it.impactDocument() }).impactMap {
                    (sections, helpers) ->
                    ImpactReviewedCollectionArtifactsDocument(
                        hashes.binary,
                        hashes.source,
                        hashes.entry,
                        hashes.facade,
                        hashes.implementation,
                        sections,
                        helpers,
                    )
                }
            ReviewedLibraryRule.KOTLIN_2_3_0_LET_V1 -> scopeDocument(hashes, ReviewedSourceSection.LET)
            ReviewedLibraryRule.KOTLIN_2_3_0_ALSO_V1 -> scopeDocument(hashes, ReviewedSourceSection.ALSO)
            ReviewedLibraryRule.KOTLIN_2_3_0_TO_V1,
            ReviewedLibraryRule.KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1,
            ReviewedLibraryRule.KOTLIN_2_3_0_PAIR_FIRST_V1,
            ReviewedLibraryRule.KOTLIN_2_3_0_PAIR_SECOND_V1 ->
                tupleSections().impactMap { sections ->
                    ImpactReviewedTupleArtifactsDocument(
                        hashes.binary,
                        hashes.source,
                        hashes.entry,
                        hashes.facade,
                        hashes.implementation,
                        sections,
                    )
                }
        }
    }

private fun ReviewedLibraryArtifact.hashes(): ImpactProjected<ArtifactHashes> =
    binaryArtifact.value
        .impactText()
        .impactZip(sourceArtifact.value.impactText())
        .impactZip(sourceEntrySha256.impactText())
        .impactZip(facadeSha256.impactText())
        .impactZip(implementationSha256.impactText())
        .impactMap { (source, implementation) ->
            ArtifactHashes(
                source.first.first.first,
                source.first.first.second,
                source.first.second,
                source.second,
                implementation,
            )
        }

private fun ReviewedLibraryArtifact.scopeDocument(
    hashes: ArtifactHashes,
    section: ReviewedSourceSection,
): ImpactProjected<ImpactReviewedArtifactsDocument> =
    sourceLines(section).impactMap {
        ImpactReviewedScopeArtifactsDocument(
            hashes.binary,
            hashes.source,
            hashes.entry,
            hashes.facade,
            hashes.implementation,
            it,
        )
    }

private fun ReviewedLibraryArtifact.collectionSections(): ImpactProjected<ImpactReviewedCollectionSectionsDocument> =
    when (rule) {
        ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1 ->
            sourceLines(ReviewedSourceSection.MAP).impactZip(sourceLines(ReviewedSourceSection.MAP_TO)).impactMap {
                (map, mapTo) ->
                ImpactReviewedCollectionSectionsDocument.Map(map, mapTo)
            }
        ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1 ->
            sourceLines(ReviewedSourceSection.MAP_2_3_0)
                .impactZip(sourceLines(ReviewedSourceSection.MAP_TO_2_3_0))
                .impactMap { (map, mapTo) -> ImpactReviewedCollectionSectionsDocument.Map(map, mapTo) }
        ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1 -> sortedSections()
        ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1 ->
            sourceLines(ReviewedSourceSection.ASSOCIATE)
                .impactZip(sourceLines(ReviewedSourceSection.ASSOCIATE_TO))
                .impactMap { (associate, associateTo) ->
                    ImpactReviewedCollectionSectionsDocument.Associate(associate, associateTo)
                }
        else -> Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    }

private fun ReviewedLibraryArtifact.sortedSections(): ImpactProjected<ImpactReviewedCollectionSectionsDocument> =
    sourceLines(ReviewedSourceSection.SORTED_BY)
        .impactZip(sourceLines(ReviewedSourceSection.SORTED_WITH))
        .impactZip(sourceLines(ReviewedSourceSection.TO_LIST))
        .impactZip(sourceLines(ReviewedSourceSection.TO_MUTABLE_LIST))
        .impactZip(sourceLines(ReviewedSourceSection.COLLECTION_TO_MUTABLE_LIST))
        .impactMap { (sorting, collectionCopy) ->
            ImpactReviewedCollectionSectionsDocument.SortedBy(
                sorting.first.first.first,
                sorting.first.first.second,
                sorting.first.second,
                sorting.second,
                collectionCopy,
            )
        }

private fun ReviewedLibraryArtifact.tupleSections(): ImpactProjected<ImpactReviewedTupleSectionsDocument> =
    if (rule == ReviewedLibraryRule.KOTLIN_2_3_0_TO_V1)
        sourceLines(ReviewedSourceSection.TO).impactZip(sourceLines(ReviewedSourceSection.PAIR)).impactMap { (to, pair)
            ->
            ImpactReviewedTupleSectionsDocument.To(to, pair)
        }
    else sourceLines(ReviewedSourceSection.PAIR).impactMap(ImpactReviewedTupleSectionsDocument::Pair)

private fun ReviewedLibraryArtifact.sourceLines(
    section: ReviewedSourceSection
): ImpactProjected<ImpactReviewedSourceLinesDocument> =
    if (section in sourceSections) section.impactDocument()
    else Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)

private fun ReviewedSourceSection.impactDocument(): ImpactProjected<ImpactReviewedSourceLinesDocument> =
    firstLine.impactOffset().impactZip(lastLine.impactOffset()).impactMap { (start, end) ->
        ImpactReviewedSourceLinesDocument(start, end)
    }

private fun ReviewedLibraryHelper.impactDocument(): ImpactProjected<ImpactReviewedLibraryHelperDocument> =
    sourceEntry
        .impactText()
        .impactZip(sourceEntrySha256.impactText())
        .impactZip(sourceSections.impactEach { it.impactDocument() })
        .impactZip(
            classes.impactEach {
                it.entry.impactText().impactZip(it.sha256.impactText()).impactMap { (entry, sha) ->
                    ImpactReviewedClassEvidenceDocument(entry, sha)
                }
            }
        )
        .impactMap { (source, classes) ->
            ImpactReviewedLibraryHelperDocument(
                kindDocument(),
                source.first.first,
                source.first.second,
                source.second,
                classes,
            )
        }

private fun ReviewedLibraryHelper.kindDocument(): ImpactReviewedLibraryHelperKindDocument =
    when (this) {
        ReviewedLibraryHelper.SORT_COMPARISON -> ImpactReviewedLibraryHelperKindDocument.SORT_COMPARISON
        ReviewedLibraryHelper.SORT_ARRAY -> ImpactReviewedLibraryHelperKindDocument.SORT_ARRAY
        ReviewedLibraryHelper.SORT_MUTABLE_LIST -> ImpactReviewedLibraryHelperKindDocument.SORT_MUTABLE_LIST
        ReviewedLibraryHelper.MAP_INSERTION -> ImpactReviewedLibraryHelperKindDocument.MAP_INSERTION
        ReviewedLibraryHelper.MAP_CAPACITY -> ImpactReviewedLibraryHelperKindDocument.MAP_CAPACITY
        ReviewedLibraryHelper.COLLECTION_CAPACITY -> ImpactReviewedLibraryHelperKindDocument.COLLECTION_CAPACITY
    }
