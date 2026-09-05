package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceBodyKindDocument
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceCoordinateUnitDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationSemanticIdentityDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationVisibilityDocument
import io.github.amichne.kast.protocol.contract.SourceEnclosingRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourceEntityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceEntityTargetDocument
import io.github.amichne.kast.protocol.contract.SourceLengthDocument
import io.github.amichne.kast.protocol.contract.SourceNestingDepthDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadContinuationStateDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SourceRegionDocument
import io.github.amichne.kast.protocol.contract.SourceRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionRangeDocument
import io.github.amichne.kast.protocol.contract.SourceSnapshotDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SourceTextWithheldReasonDocument
import io.github.amichne.kast.protocol.contract.SourceUnresolvedReasonDocument
import io.github.amichne.kast.protocol.contract.SourceVisibilitySelectionDocument

internal object CanonicalSourceReadSerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)

    val request = factory.create(
        SourceReadRequestWireDocument.serializer(),
        SourceReadRequest::toWireDocument,
        SourceReadRequestWireDocument::toContract,
    )
    val result = factory.create(
        SourceReadResultWireDocument.serializer(),
        SourceReadResult::toWireDocument,
        SourceReadResultWireDocument::toContract,
    )
    val qualification = factory.create(
        SourceReadQualificationWireDocument.serializer(),
        SourceReadQualification::toWireDocument,
        SourceReadQualificationWireDocument::toContract,
    )
    val rejection = factory.create(
        SourceReadRejectionWireDocument.serializer(),
        SourceReadRejection::toWireDocument,
        { WireDocumentConversion.Converted(it.toContract()) },
    )
}

private fun SourceReadRequest.toWireDocument(): SourceReadRequestWireDocument =
    SourceReadRequestWireDocument(
        anchor = anchor.toWireDocument(),
        region = region.toWireDocument(),
        entities = entities.toWireDocument(),
        text = text.toWireDocument(),
        entityLimit = entityLimit.value,
        textByteLimit = textByteLimit.value,
        page = page.toWireDocument(),
    )

private fun SourceReadRequestWireDocument.toContract(): WireDocumentConversion<SourceReadRequest> =
    anchor.toContract().flatMapConverted { admittedAnchor ->
        region.toContract().flatMapConverted { admittedRegion ->
            entities.toContract().flatMapConverted { admittedEntities ->
                text.toContract().flatMapConverted { admittedText ->
                    entityLimit.sourceEntityLimit().flatMapConverted { admittedEntityLimit ->
                        textByteLimit.sourceTextByteLimit().flatMapConverted { admittedTextLimit ->
                            page.toContract().mapConverted { admittedPage ->
                                SourceReadRequest(
                                    admittedAnchor,
                                    admittedRegion,
                                    admittedEntities,
                                    admittedText,
                                    admittedEntityLimit,
                                    admittedTextLimit,
                                    admittedPage,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

private fun SourceReadAnchorDocument.toWireDocument(): SourceReadAnchorWireDocument = when (this) {
    is SourceReadAnchorDocument.Candidate -> SourceReadAnchorWireDocument.Candidate(selector.value)
    is SourceReadAnchorDocument.Symbol -> SourceReadAnchorWireDocument.Symbol(selector.value)
    is SourceReadAnchorDocument.Source -> SourceReadAnchorWireDocument.Source(selector.value)
}

private fun SourceReadAnchorWireDocument.toContract():
    WireDocumentConversion<SourceReadAnchorDocument> = when (this) {
    is SourceReadAnchorWireDocument.Candidate -> selector.protocolText()
        .mapConverted(SourceReadAnchorDocument::Candidate)
    is SourceReadAnchorWireDocument.Symbol -> selector.protocolText()
        .mapConverted(SourceReadAnchorDocument::Symbol)
    is SourceReadAnchorWireDocument.Source -> selector.protocolText()
        .mapConverted(SourceReadAnchorDocument::Source)
}

private fun SourceRegionSelectionDocument.toWireDocument():
    SourceRegionSelectionWireDocument = when (this) {
    SourceRegionSelectionDocument.Anchor -> SourceRegionSelectionWireDocument.Anchor
    is SourceRegionSelectionDocument.Body ->
        SourceRegionSelectionWireDocument.Body(kind.toWireDocument())
    SourceRegionSelectionDocument.File -> SourceRegionSelectionWireDocument.File
    is SourceRegionSelectionDocument.Enclosing ->
        SourceRegionSelectionWireDocument.Enclosing(kind.toWireDocument())
}

private fun SourceRegionSelectionWireDocument.toContract():
    WireDocumentConversion<SourceRegionSelectionDocument> = WireDocumentConversion.Converted(
    when (this) {
        SourceRegionSelectionWireDocument.Anchor -> SourceRegionSelectionDocument.Anchor
        is SourceRegionSelectionWireDocument.Body ->
            SourceRegionSelectionDocument.Body(kind.toContract())
        SourceRegionSelectionWireDocument.File -> SourceRegionSelectionDocument.File
        is SourceRegionSelectionWireDocument.Enclosing ->
            SourceRegionSelectionDocument.Enclosing(kind.toContract())
    },
)

private fun SourceEntitySelectionDocument.toWireDocument():
    SourceEntitySelectionWireDocument = when (this) {
    SourceEntitySelectionDocument.None -> SourceEntitySelectionWireDocument.None
    is SourceEntitySelectionDocument.Matching -> SourceEntitySelectionWireDocument.Matching(
        containment.toWireDocument(),
        filters.map(SourceEntityFilterDocument::toWireDocument),
    )
}

private fun SourceEntitySelectionWireDocument.toContract():
    WireDocumentConversion<SourceEntitySelectionDocument> = when (this) {
    SourceEntitySelectionWireDocument.None ->
        WireDocumentConversion.Converted(SourceEntitySelectionDocument.None)
    is SourceEntitySelectionWireDocument.Matching -> filters
        .convertEach(SourceEntityFilterWireDocument::toContract)
        .flatMapConverted { admittedFilters ->
            if (!admittedFilters.isCanonicalEntityFilters()) {
                WireDocumentConversion.Rejected
            } else {
                WireDocumentConversion.Converted(
                    SourceEntitySelectionDocument.Matching(
                        containment.toContract(),
                        admittedFilters,
                    ),
                )
            }
        }
}

private fun List<SourceEntityFilterDocument>.isCanonicalEntityFilters(): Boolean {
    if (isEmpty()) return false
    val keys = map { filter ->
        when (filter) {
            is SourceEntityFilterDocument.Declarations -> 0
            SourceEntityFilterDocument.Parameters -> 1
            SourceEntityFilterDocument.Calls -> 2
            SourceEntityFilterDocument.References -> 3
        }
    }
    return keys == keys.distinct().sorted()
}

private fun SourceEntityFilterDocument.toWireDocument(): SourceEntityFilterWireDocument =
    when (this) {
        is SourceEntityFilterDocument.Declarations ->
            SourceEntityFilterWireDocument.Declarations(
                kinds.map(SourceDeclarationKindDocument::toWireDocument),
                visibility.toWireDocument(),
            )
        SourceEntityFilterDocument.Parameters -> SourceEntityFilterWireDocument.Parameters
        SourceEntityFilterDocument.Calls -> SourceEntityFilterWireDocument.Calls
        SourceEntityFilterDocument.References -> SourceEntityFilterWireDocument.References
    }

private fun SourceEntityFilterWireDocument.toContract():
    WireDocumentConversion<SourceEntityFilterDocument> = when (this) {
    is SourceEntityFilterWireDocument.Declarations -> {
        val admittedKinds = kinds.map(SourceDeclarationKindWireDocument::toContract)
        if (admittedKinds.isEmpty() || admittedKinds != admittedKinds.distinct().sortedBy { it.ordinal }) {
            WireDocumentConversion.Rejected
        } else {
            visibility.toContract().mapConverted { admittedVisibility ->
                SourceEntityFilterDocument.Declarations(admittedKinds, admittedVisibility)
            }
        }
    }
    SourceEntityFilterWireDocument.Parameters ->
        WireDocumentConversion.Converted(SourceEntityFilterDocument.Parameters)
    SourceEntityFilterWireDocument.Calls ->
        WireDocumentConversion.Converted(SourceEntityFilterDocument.Calls)
    SourceEntityFilterWireDocument.References ->
        WireDocumentConversion.Converted(SourceEntityFilterDocument.References)
}

private fun SourceVisibilitySelectionDocument.toWireDocument():
    SourceVisibilitySelectionWireDocument = when (this) {
    SourceVisibilitySelectionDocument.Any -> SourceVisibilitySelectionWireDocument.Any
    is SourceVisibilitySelectionDocument.Exact -> SourceVisibilitySelectionWireDocument.Exact(
        values.map(SourceDeclarationVisibilityDocument::toWireDocument),
    )
}

private fun SourceVisibilitySelectionWireDocument.toContract():
    WireDocumentConversion<SourceVisibilitySelectionDocument> = when (this) {
    SourceVisibilitySelectionWireDocument.Any ->
        WireDocumentConversion.Converted(SourceVisibilitySelectionDocument.Any)
    is SourceVisibilitySelectionWireDocument.Exact -> {
        val admitted = values.map(SourceDeclarationVisibilityWireDocument::toContract)
        if (admitted.isEmpty() || admitted != admitted.distinct().sortedBy { it.ordinal }) {
            WireDocumentConversion.Rejected
        } else {
            WireDocumentConversion.Converted(SourceVisibilitySelectionDocument.Exact(admitted))
        }
    }
}

private fun SourceTextRequestDocument.toWireDocument(): SourceTextRequestWireDocument = when (this) {
    SourceTextRequestDocument.Complete -> SourceTextRequestWireDocument.Complete
    SourceTextRequestDocument.None -> SourceTextRequestWireDocument.None
    is SourceTextRequestDocument.Window -> SourceTextRequestWireDocument.Window(
        beforeLines.value,
        afterLines.value,
    )
}

private fun SourceTextRequestWireDocument.toContract():
    WireDocumentConversion<SourceTextRequestDocument> = when (this) {
    SourceTextRequestWireDocument.Complete ->
        WireDocumentConversion.Converted(SourceTextRequestDocument.Complete)
    SourceTextRequestWireDocument.None ->
        WireDocumentConversion.Converted(SourceTextRequestDocument.None)
    is SourceTextRequestWireDocument.Window -> combineConverted(
        beforeLines.sourceLineCount(),
        afterLines.sourceLineCount(),
    ) { before, after -> SourceTextRequestDocument.Window(before, after) }
}

private fun SourceReadPageDocument.toWireDocument(): SourceReadPageWireDocument = when (this) {
    SourceReadPageDocument.First -> SourceReadPageWireDocument.First
    is SourceReadPageDocument.Continue ->
        SourceReadPageWireDocument.Continue(continuation.value)
}

private fun SourceReadPageWireDocument.toContract():
    WireDocumentConversion<SourceReadPageDocument> = when (this) {
    SourceReadPageWireDocument.First ->
        WireDocumentConversion.Converted(SourceReadPageDocument.First)
    is SourceReadPageWireDocument.Continue -> continuation.protocolText()
        .mapConverted(SourceReadPageDocument::Continue)
}

private fun SourceReadResult.toWireDocument(): SourceReadResultWireDocument =
    SourceReadResultWireDocument(
        snapshot.toWireDocument(),
        region.toWireDocument(),
        entities.values.map(SourceEntityDocument::toWireDocument),
        text.toWireDocument(),
    )

private fun SourceReadResultWireDocument.toContract(): WireDocumentConversion<SourceReadResult> =
    snapshot.toContract().flatMapConverted { admittedSnapshot ->
        region.toContract().flatMapConverted { admittedRegion ->
            entities.convertEach(SourceEntityWireDocument::toContract).flatMapConverted {
                admittedEntities ->
                BoundedProtocolList.create(admittedEntities).toWireDocumentConversion()
                    .flatMapConverted { boundedEntities ->
                        text.toContract().flatMapConverted { admittedText ->
                            if (!isCoherent(admittedSnapshot, admittedRegion, admittedEntities, admittedText)) {
                                WireDocumentConversion.Rejected
                            } else {
                                WireDocumentConversion.Converted(
                                    SourceReadResult(
                                        admittedSnapshot,
                                        admittedRegion,
                                        boundedEntities,
                                        admittedText,
                                    ),
                                )
                            }
                        }
                    }
            }
        }
    }

private fun SourceReadResultWireDocument.isCoherent(
    snapshot: SourceSnapshotDocument,
    region: SourceRegionDocument,
    entities: List<SourceEntityDocument>,
    text: SourceTextProjectionDocument,
): Boolean {
    val snapshotLength = snapshot.length.value
    fun SourceSelectionDocument.isWithinSnapshot(): Boolean =
        range.endExclusive.value <= snapshotLength
    if (!region.selection.isWithinSnapshot()) return false
    if (entities.any { !it.selection.isWithinSnapshot() }) return false
    return when (text) {
        SourceTextProjectionDocument.NotRequested,
        is SourceTextProjectionDocument.Withheld,
        -> true
        is SourceTextProjectionDocument.Returned -> text.selection.isWithinSnapshot() &&
            text.text.value.length ==
            text.selection.range.endExclusive.value - text.selection.range.startInclusive.value &&
            text.lines.startInclusive.value <= text.selection.range.startInclusive.value.toLong() + 1 &&
            text.lines.endInclusive.value <= snapshotLength.toLong() + 1 &&
            text.lines.endInclusive.value - text.lines.startInclusive.value ==
                text.text.value.dropLast(1).count { it == '\n' }.toLong()
    }
}

private fun SourceSnapshotDocument.toWireDocument(): SourceSnapshotWireDocument =
    SourceSnapshotWireDocument(
        canonicalRoot.value,
        generation,
        sourceState.value,
        file.value,
        textIdentity.value,
        coordinateUnit.toWireDocument(),
        length.value,
    )

private fun SourceSnapshotWireDocument.toContract(): WireDocumentConversion<SourceSnapshotDocument> {
    if (generation < 0L) return WireDocumentConversion.Rejected
    return canonicalRoot.protocolText().flatMapConverted { admittedRoot ->
        sourceState.protocolText().flatMapConverted { admittedState ->
            file.protocolText().flatMapConverted { admittedFile ->
                textIdentity.protocolText().flatMapConverted { admittedIdentity ->
                    length.sourceLength().mapConverted { admittedLength ->
                        SourceSnapshotDocument(
                            admittedRoot,
                            generation,
                            admittedState,
                            admittedFile,
                            admittedIdentity,
                            coordinateUnit.toContract(),
                            admittedLength,
                        )
                    }
                }
            }
        }
    }
}

private fun SourceSelectionDocument.toWireDocument(): SourceSelectionWireDocument =
    SourceSelectionWireDocument(selector.value, range.toWireDocument())

private fun SourceSelectionWireDocument.toContract():
    WireDocumentConversion<SourceSelectionDocument> = combineConverted(
    selector.protocolText(),
    range.toContract(),
    ::SourceSelectionDocument,
)

private fun SourceSelectionRangeDocument.toWireDocument(): SourceSelectionRangeWireDocument =
    SourceSelectionRangeWireDocument(startInclusive.value, endExclusive.value)

private fun SourceSelectionRangeWireDocument.toContract():
    WireDocumentConversion<SourceSelectionRangeDocument> = combineConverted(
    startInclusive.protocolOffset(),
    endExclusive.protocolOffset(),
) { start, end -> SourceSelectionRangeDocument.create(start, end).toWireDocumentConversion() }
    .flattenConverted()

private fun SourceRegionDocument.toWireDocument(): SourceRegionWireDocument =
    SourceRegionWireDocument(kind.toWireDocument(), selection.toWireDocument())

private fun SourceRegionWireDocument.toContract(): WireDocumentConversion<SourceRegionDocument> =
    selection.toContract().mapConverted { SourceRegionDocument(kind.toContract(), it) }

private fun SourceEntityDocument.toWireDocument(): SourceEntityWireDocument = when (this) {
    is SourceEntityDocument.Declaration -> SourceEntityWireDocument.Declaration(
        kind.toWireDocument(),
        name.value,
        visibility.toWireDocument(),
        nestingDepth.value,
        parentSelector.value,
        selection.toWireDocument(),
        semanticIdentity.toWireDocument(),
    )
    is SourceEntityDocument.ValueParameter -> SourceEntityWireDocument.ValueParameter(
        name.value,
        nestingDepth.value,
        parentSelector.value,
        selection.toWireDocument(),
    )
    is SourceEntityDocument.Call -> SourceEntityWireDocument.Call(
        nestingDepth.value,
        parentSelector.value,
        selection.toWireDocument(),
        callee.toWireDocument(),
        target.toWireDocument(),
    )
    is SourceEntityDocument.Reference -> SourceEntityWireDocument.Reference(
        name.value,
        nestingDepth.value,
        parentSelector.value,
        selection.toWireDocument(),
        target.toWireDocument(),
    )
}

private fun SourceEntityWireDocument.toContract(): WireDocumentConversion<SourceEntityDocument> =
    when (this) {
        is SourceEntityWireDocument.Declaration -> name.protocolText().flatMapConverted {
            admittedName ->
            nestingDepth.sourceNestingDepth().flatMapConverted { admittedDepth ->
                parentSelector.protocolText().flatMapConverted { admittedParent ->
                    selection.toContract().flatMapConverted { admittedSelection ->
                        semanticIdentity.toContract().mapConverted { admittedIdentity ->
                            SourceEntityDocument.Declaration(
                                kind.toContract(),
                                admittedName,
                                visibility.toContract(),
                                admittedDepth,
                                admittedParent,
                                admittedSelection,
                                admittedIdentity,
                            )
                        }
                    }
                }
            }
        }
        is SourceEntityWireDocument.ValueParameter -> name.protocolText().flatMapConverted {
            admittedName ->
            nestingDepth.sourceNestingDepth().flatMapConverted { admittedDepth ->
                parentSelector.protocolText().flatMapConverted { admittedParent ->
                    selection.toContract().mapConverted { admittedSelection ->
                        SourceEntityDocument.ValueParameter(
                            admittedName,
                            admittedDepth,
                            admittedParent,
                            admittedSelection,
                        )
                    }
                }
            }
        }
        is SourceEntityWireDocument.Call -> nestingDepth.sourceNestingDepth().flatMapConverted {
            admittedDepth ->
            parentSelector.protocolText().flatMapConverted { admittedParent ->
                selection.toContract().flatMapConverted { admittedSelection ->
                    callee.toContract().flatMapConverted { admittedCallee ->
                        target.toContract().mapConverted { admittedTarget ->
                            SourceEntityDocument.Call(
                                admittedDepth,
                                admittedParent,
                                admittedSelection,
                                admittedCallee,
                                admittedTarget,
                            )
                        }
                    }
                }
            }
        }
        is SourceEntityWireDocument.Reference -> name.protocolText().flatMapConverted {
            admittedName ->
            nestingDepth.sourceNestingDepth().flatMapConverted { admittedDepth ->
                parentSelector.protocolText().flatMapConverted { admittedParent ->
                    selection.toContract().flatMapConverted { admittedSelection ->
                        target.toContract().mapConverted { admittedTarget ->
                            SourceEntityDocument.Reference(
                                admittedName,
                                admittedDepth,
                                admittedParent,
                                admittedSelection,
                                admittedTarget,
                            )
                        }
                    }
                }
            }
        }
    }

private fun SourceDeclarationSemanticIdentityDocument.toWireDocument():
    SourceDeclarationSemanticIdentityWireDocument = when (this) {
    is SourceDeclarationSemanticIdentityDocument.Candidate ->
        SourceDeclarationSemanticIdentityWireDocument.Candidate(selector.value)
}

private fun SourceDeclarationSemanticIdentityWireDocument.toContract():
    WireDocumentConversion<SourceDeclarationSemanticIdentityDocument> = when (this) {
    is SourceDeclarationSemanticIdentityWireDocument.Candidate -> selector.protocolText()
        .mapConverted(SourceDeclarationSemanticIdentityDocument::Candidate)
}

private fun SourceEntityTargetDocument.toWireDocument(): SourceEntityTargetWireDocument =
    when (this) {
        is SourceEntityTargetDocument.Candidate ->
            SourceEntityTargetWireDocument.Candidate(selector.value)
        is SourceEntityTargetDocument.Local -> SourceEntityTargetWireDocument.Local(selector.value)
        is SourceEntityTargetDocument.Unresolved ->
            SourceEntityTargetWireDocument.Unresolved(reason.toWireDocument())
    }

private fun SourceEntityTargetWireDocument.toContract():
    WireDocumentConversion<SourceEntityTargetDocument> = when (this) {
    is SourceEntityTargetWireDocument.Candidate -> selector.protocolText()
        .mapConverted(SourceEntityTargetDocument::Candidate)
    is SourceEntityTargetWireDocument.Local -> selector.protocolText()
        .mapConverted(SourceEntityTargetDocument::Local)
    is SourceEntityTargetWireDocument.Unresolved -> WireDocumentConversion.Converted(
        SourceEntityTargetDocument.Unresolved(reason.toContract()),
    )
}

private fun SourceTextProjectionDocument.toWireDocument(): SourceTextProjectionWireDocument =
    when (this) {
        SourceTextProjectionDocument.NotRequested -> SourceTextProjectionWireDocument.NotRequested
        is SourceTextProjectionDocument.Returned -> SourceTextProjectionWireDocument.Returned(
            selection.toWireDocument(),
            text.value,
            SourceLineRangeWireDocument(lines.startInclusive.value, lines.endInclusive.value),
        )
        is SourceTextProjectionDocument.Withheld -> SourceTextProjectionWireDocument.Withheld(
            reason.toWireDocument(),
        )
    }

private fun SourceTextProjectionWireDocument.toContract():
    WireDocumentConversion<SourceTextProjectionDocument> = when (this) {
    SourceTextProjectionWireDocument.NotRequested ->
        WireDocumentConversion.Converted(SourceTextProjectionDocument.NotRequested)
    is SourceTextProjectionWireDocument.Returned -> combineConverted(
        selection.toContract(),
        text.protocolSourceText(),
        io.github.amichne.kast.protocol.contract.SourceLineRangeDocument.parse(
            lines.startInclusive, lines.endInclusive,
        ).toWireDocumentConversion(),
        SourceTextProjectionDocument::Returned,
    )
    is SourceTextProjectionWireDocument.Withheld -> WireDocumentConversion.Converted(
        SourceTextProjectionDocument.Withheld(reason.toContract()),
    )
}

private fun SourceReadQualification.toWireDocument(): SourceReadQualificationWireDocument =
    SourceReadQualificationWireDocument(
        knownMinimumEntityCount.value,
        limitations.map(SourceReadLimitationDocument::toWireDocument),
        continuation.toWireDocument(),
    )

private fun SourceReadQualificationWireDocument.toContract():
    WireDocumentConversion<SourceReadQualification> =
    knownMinimumEntityCount.sourceEntityCount().flatMapConverted { admittedCount ->
        val admittedLimitations = limitations.map(SourceReadLimitationWireDocument::toContract)
        continuation.toContract().flatMapConverted { admittedContinuation ->
            SourceReadQualification.create(
                admittedCount,
                admittedLimitations,
                admittedContinuation,
            ).toWireDocumentConversion()
        }
    }

private fun SourceReadContinuationStateDocument.toWireDocument():
    SourceReadContinuationStateWireDocument = when (this) {
    SourceReadContinuationStateDocument.Unavailable ->
        SourceReadContinuationStateWireDocument.Unavailable
    is SourceReadContinuationStateDocument.Available ->
        SourceReadContinuationStateWireDocument.Available(continuation.value)
}

private fun SourceReadContinuationStateWireDocument.toContract():
    WireDocumentConversion<SourceReadContinuationStateDocument> = when (this) {
    SourceReadContinuationStateWireDocument.Unavailable -> WireDocumentConversion.Converted(
        SourceReadContinuationStateDocument.Unavailable,
    )
    is SourceReadContinuationStateWireDocument.Available -> continuation.protocolText()
        .mapConverted(SourceReadContinuationStateDocument::Available)
}

private fun SourceReadRejection.toWireDocument(): SourceReadRejectionWireDocument = when (this) {
    SourceReadRejection.WORKSPACE_NOT_READY -> SourceReadRejectionWireDocument.WORKSPACE_NOT_READY
    SourceReadRejection.WORKSPACE_ROOT_MISMATCH ->
        SourceReadRejectionWireDocument.WORKSPACE_ROOT_MISMATCH
    SourceReadRejection.STALE_GENERATION -> SourceReadRejectionWireDocument.STALE_GENERATION
    SourceReadRejection.SOURCE_STATE_MISMATCH ->
        SourceReadRejectionWireDocument.SOURCE_STATE_MISMATCH
    SourceReadRejection.CANDIDATE_STALE -> SourceReadRejectionWireDocument.CANDIDATE_STALE
    SourceReadRejection.SOURCE_SELECTOR_STALE ->
        SourceReadRejectionWireDocument.SOURCE_SELECTOR_STALE
    SourceReadRejection.SOURCE_SNAPSHOT_MISMATCH ->
        SourceReadRejectionWireDocument.SOURCE_SNAPSHOT_MISMATCH
    SourceReadRejection.SOURCE_UNAVAILABLE -> SourceReadRejectionWireDocument.SOURCE_UNAVAILABLE
    SourceReadRejection.DOCUMENT_DIRTY -> SourceReadRejectionWireDocument.DOCUMENT_DIRTY
    SourceReadRejection.PSI_DOCUMENT_UNCOMMITTED ->
        SourceReadRejectionWireDocument.PSI_DOCUMENT_UNCOMMITTED
    SourceReadRejection.OUTSIDE_SOURCE_SCOPE ->
        SourceReadRejectionWireDocument.OUTSIDE_SOURCE_SCOPE
    SourceReadRejection.ANCHOR_NOT_FOUND -> SourceReadRejectionWireDocument.ANCHOR_NOT_FOUND
    SourceReadRejection.AMBIGUOUS_ANCHOR -> SourceReadRejectionWireDocument.AMBIGUOUS_ANCHOR
    SourceReadRejection.REGION_NOT_APPLICABLE ->
        SourceReadRejectionWireDocument.REGION_NOT_APPLICABLE
    SourceReadRejection.REGION_ABSENT -> SourceReadRejectionWireDocument.REGION_ABSENT
    SourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE ->
        SourceReadRejectionWireDocument.COMPILER_ANALYSIS_UNAVAILABLE
    SourceReadRejection.CONTRACT_VIOLATION -> SourceReadRejectionWireDocument.CONTRACT_VIOLATION
}

private fun SourceReadRejectionWireDocument.toContract(): SourceReadRejection = when (this) {
    SourceReadRejectionWireDocument.WORKSPACE_NOT_READY -> SourceReadRejection.WORKSPACE_NOT_READY
    SourceReadRejectionWireDocument.WORKSPACE_ROOT_MISMATCH ->
        SourceReadRejection.WORKSPACE_ROOT_MISMATCH
    SourceReadRejectionWireDocument.STALE_GENERATION -> SourceReadRejection.STALE_GENERATION
    SourceReadRejectionWireDocument.SOURCE_STATE_MISMATCH ->
        SourceReadRejection.SOURCE_STATE_MISMATCH
    SourceReadRejectionWireDocument.CANDIDATE_STALE -> SourceReadRejection.CANDIDATE_STALE
    SourceReadRejectionWireDocument.SOURCE_SELECTOR_STALE ->
        SourceReadRejection.SOURCE_SELECTOR_STALE
    SourceReadRejectionWireDocument.SOURCE_SNAPSHOT_MISMATCH ->
        SourceReadRejection.SOURCE_SNAPSHOT_MISMATCH
    SourceReadRejectionWireDocument.SOURCE_UNAVAILABLE -> SourceReadRejection.SOURCE_UNAVAILABLE
    SourceReadRejectionWireDocument.DOCUMENT_DIRTY -> SourceReadRejection.DOCUMENT_DIRTY
    SourceReadRejectionWireDocument.PSI_DOCUMENT_UNCOMMITTED ->
        SourceReadRejection.PSI_DOCUMENT_UNCOMMITTED
    SourceReadRejectionWireDocument.OUTSIDE_SOURCE_SCOPE ->
        SourceReadRejection.OUTSIDE_SOURCE_SCOPE
    SourceReadRejectionWireDocument.ANCHOR_NOT_FOUND -> SourceReadRejection.ANCHOR_NOT_FOUND
    SourceReadRejectionWireDocument.AMBIGUOUS_ANCHOR -> SourceReadRejection.AMBIGUOUS_ANCHOR
    SourceReadRejectionWireDocument.REGION_NOT_APPLICABLE ->
        SourceReadRejection.REGION_NOT_APPLICABLE
    SourceReadRejectionWireDocument.REGION_ABSENT -> SourceReadRejection.REGION_ABSENT
    SourceReadRejectionWireDocument.COMPILER_ANALYSIS_UNAVAILABLE ->
        SourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE
    SourceReadRejectionWireDocument.CONTRACT_VIOLATION -> SourceReadRejection.CONTRACT_VIOLATION
}

private fun SourceBodyKindDocument.toWireDocument(): SourceBodyKindWireDocument = when (this) {
    SourceBodyKindDocument.CALLABLE -> SourceBodyKindWireDocument.CALLABLE
    SourceBodyKindDocument.CLASS -> SourceBodyKindWireDocument.CLASS
}

private fun SourceBodyKindWireDocument.toContract(): SourceBodyKindDocument = when (this) {
    SourceBodyKindWireDocument.CALLABLE -> SourceBodyKindDocument.CALLABLE
    SourceBodyKindWireDocument.CLASS -> SourceBodyKindDocument.CLASS
}

private fun SourceEnclosingRegionKindDocument.toWireDocument():
    SourceEnclosingRegionKindWireDocument = when (this) {
    SourceEnclosingRegionKindDocument.DECLARATION ->
        SourceEnclosingRegionKindWireDocument.DECLARATION
    SourceEnclosingRegionKindDocument.CALLABLE_BODY ->
        SourceEnclosingRegionKindWireDocument.CALLABLE_BODY
    SourceEnclosingRegionKindDocument.CLASS_BODY ->
        SourceEnclosingRegionKindWireDocument.CLASS_BODY
}

private fun SourceEnclosingRegionKindWireDocument.toContract():
    SourceEnclosingRegionKindDocument = when (this) {
    SourceEnclosingRegionKindWireDocument.DECLARATION ->
        SourceEnclosingRegionKindDocument.DECLARATION
    SourceEnclosingRegionKindWireDocument.CALLABLE_BODY ->
        SourceEnclosingRegionKindDocument.CALLABLE_BODY
    SourceEnclosingRegionKindWireDocument.CLASS_BODY ->
        SourceEnclosingRegionKindDocument.CLASS_BODY
}

private fun SourceContainmentDocument.toWireDocument(): SourceContainmentWireDocument = when (this) {
    SourceContainmentDocument.DIRECT -> SourceContainmentWireDocument.DIRECT
    SourceContainmentDocument.DESCENDANTS -> SourceContainmentWireDocument.DESCENDANTS
}

private fun SourceContainmentWireDocument.toContract(): SourceContainmentDocument = when (this) {
    SourceContainmentWireDocument.DIRECT -> SourceContainmentDocument.DIRECT
    SourceContainmentWireDocument.DESCENDANTS -> SourceContainmentDocument.DESCENDANTS
}

private fun SourceDeclarationKindDocument.toWireDocument():
    SourceDeclarationKindWireDocument = when (this) {
    SourceDeclarationKindDocument.CLASSLIKE -> SourceDeclarationKindWireDocument.CLASSLIKE
    SourceDeclarationKindDocument.CONSTRUCTOR -> SourceDeclarationKindWireDocument.CONSTRUCTOR
    SourceDeclarationKindDocument.FUNCTION -> SourceDeclarationKindWireDocument.FUNCTION
    SourceDeclarationKindDocument.PROPERTY -> SourceDeclarationKindWireDocument.PROPERTY
    SourceDeclarationKindDocument.TYPE_ALIAS -> SourceDeclarationKindWireDocument.TYPE_ALIAS
}

private fun SourceDeclarationKindWireDocument.toContract():
    SourceDeclarationKindDocument = when (this) {
    SourceDeclarationKindWireDocument.CLASSLIKE -> SourceDeclarationKindDocument.CLASSLIKE
    SourceDeclarationKindWireDocument.CONSTRUCTOR -> SourceDeclarationKindDocument.CONSTRUCTOR
    SourceDeclarationKindWireDocument.FUNCTION -> SourceDeclarationKindDocument.FUNCTION
    SourceDeclarationKindWireDocument.PROPERTY -> SourceDeclarationKindDocument.PROPERTY
    SourceDeclarationKindWireDocument.TYPE_ALIAS -> SourceDeclarationKindDocument.TYPE_ALIAS
}

private fun SourceDeclarationVisibilityDocument.toWireDocument():
    SourceDeclarationVisibilityWireDocument = when (this) {
    SourceDeclarationVisibilityDocument.PUBLIC -> SourceDeclarationVisibilityWireDocument.PUBLIC
    SourceDeclarationVisibilityDocument.PROTECTED ->
        SourceDeclarationVisibilityWireDocument.PROTECTED
    SourceDeclarationVisibilityDocument.INTERNAL -> SourceDeclarationVisibilityWireDocument.INTERNAL
    SourceDeclarationVisibilityDocument.PRIVATE -> SourceDeclarationVisibilityWireDocument.PRIVATE
    SourceDeclarationVisibilityDocument.LOCAL -> SourceDeclarationVisibilityWireDocument.LOCAL
}

private fun SourceDeclarationVisibilityWireDocument.toContract():
    SourceDeclarationVisibilityDocument = when (this) {
    SourceDeclarationVisibilityWireDocument.PUBLIC -> SourceDeclarationVisibilityDocument.PUBLIC
    SourceDeclarationVisibilityWireDocument.PROTECTED ->
        SourceDeclarationVisibilityDocument.PROTECTED
    SourceDeclarationVisibilityWireDocument.INTERNAL -> SourceDeclarationVisibilityDocument.INTERNAL
    SourceDeclarationVisibilityWireDocument.PRIVATE -> SourceDeclarationVisibilityDocument.PRIVATE
    SourceDeclarationVisibilityWireDocument.LOCAL -> SourceDeclarationVisibilityDocument.LOCAL
}

private fun SourceCoordinateUnitDocument.toWireDocument(): SourceCoordinateUnitWireDocument =
    when (this) {
        SourceCoordinateUnitDocument.UTF16_CODE_UNIT ->
            SourceCoordinateUnitWireDocument.UTF16_CODE_UNIT
    }

private fun SourceCoordinateUnitWireDocument.toContract(): SourceCoordinateUnitDocument =
    when (this) {
        SourceCoordinateUnitWireDocument.UTF16_CODE_UNIT ->
            SourceCoordinateUnitDocument.UTF16_CODE_UNIT
    }

private fun SourceRegionKindDocument.toWireDocument(): SourceRegionKindWireDocument = when (this) {
    SourceRegionKindDocument.ANCHOR -> SourceRegionKindWireDocument.ANCHOR
    SourceRegionKindDocument.DECLARATION -> SourceRegionKindWireDocument.DECLARATION
    SourceRegionKindDocument.CALLABLE_BODY -> SourceRegionKindWireDocument.CALLABLE_BODY
    SourceRegionKindDocument.CLASS_BODY -> SourceRegionKindWireDocument.CLASS_BODY
    SourceRegionKindDocument.FILE -> SourceRegionKindWireDocument.FILE
    SourceRegionKindDocument.WINDOW -> SourceRegionKindWireDocument.WINDOW
}

private fun SourceRegionKindWireDocument.toContract(): SourceRegionKindDocument = when (this) {
    SourceRegionKindWireDocument.ANCHOR -> SourceRegionKindDocument.ANCHOR
    SourceRegionKindWireDocument.DECLARATION -> SourceRegionKindDocument.DECLARATION
    SourceRegionKindWireDocument.CALLABLE_BODY -> SourceRegionKindDocument.CALLABLE_BODY
    SourceRegionKindWireDocument.CLASS_BODY -> SourceRegionKindDocument.CLASS_BODY
    SourceRegionKindWireDocument.FILE -> SourceRegionKindDocument.FILE
    SourceRegionKindWireDocument.WINDOW -> SourceRegionKindDocument.WINDOW
}

private fun SourceUnresolvedReasonDocument.toWireDocument(): SourceUnresolvedReasonWireDocument =
    when (this) {
        SourceUnresolvedReasonDocument.NAME_NOT_FOUND -> SourceUnresolvedReasonWireDocument.NAME_NOT_FOUND
        SourceUnresolvedReasonDocument.AMBIGUOUS -> SourceUnresolvedReasonWireDocument.AMBIGUOUS
        SourceUnresolvedReasonDocument.ERROR_TYPE -> SourceUnresolvedReasonWireDocument.ERROR_TYPE
        SourceUnresolvedReasonDocument.UNSUPPORTED_TARGET ->
            SourceUnresolvedReasonWireDocument.UNSUPPORTED_TARGET
    }

private fun SourceUnresolvedReasonWireDocument.toContract(): SourceUnresolvedReasonDocument =
    when (this) {
        SourceUnresolvedReasonWireDocument.NAME_NOT_FOUND -> SourceUnresolvedReasonDocument.NAME_NOT_FOUND
        SourceUnresolvedReasonWireDocument.AMBIGUOUS -> SourceUnresolvedReasonDocument.AMBIGUOUS
        SourceUnresolvedReasonWireDocument.ERROR_TYPE -> SourceUnresolvedReasonDocument.ERROR_TYPE
        SourceUnresolvedReasonWireDocument.UNSUPPORTED_TARGET ->
            SourceUnresolvedReasonDocument.UNSUPPORTED_TARGET
    }

private fun SourceTextWithheldReasonDocument.toWireDocument():
    SourceTextWithheldReasonWireDocument = when (this) {
    SourceTextWithheldReasonDocument.BYTE_LIMIT_REACHED ->
        SourceTextWithheldReasonWireDocument.BYTE_LIMIT_REACHED
    SourceTextWithheldReasonDocument.PROVIDER_UNAVAILABLE ->
        SourceTextWithheldReasonWireDocument.PROVIDER_UNAVAILABLE
}

private fun SourceTextWithheldReasonWireDocument.toContract():
    SourceTextWithheldReasonDocument = when (this) {
    SourceTextWithheldReasonWireDocument.BYTE_LIMIT_REACHED ->
        SourceTextWithheldReasonDocument.BYTE_LIMIT_REACHED
    SourceTextWithheldReasonWireDocument.PROVIDER_UNAVAILABLE ->
        SourceTextWithheldReasonDocument.PROVIDER_UNAVAILABLE
}

private fun SourceReadLimitationDocument.toWireDocument(): SourceReadLimitationWireDocument =
    when (this) {
        SourceReadLimitationDocument.ENTITY_LIMIT_REACHED ->
            SourceReadLimitationWireDocument.ENTITY_LIMIT_REACHED
        SourceReadLimitationDocument.TEXT_BYTE_LIMIT_REACHED ->
            SourceReadLimitationWireDocument.TEXT_BYTE_LIMIT_REACHED
        SourceReadLimitationDocument.WORK_LIMIT_REACHED ->
            SourceReadLimitationWireDocument.WORK_LIMIT_REACHED
        SourceReadLimitationDocument.TIME_LIMIT_REACHED ->
            SourceReadLimitationWireDocument.TIME_LIMIT_REACHED
        SourceReadLimitationDocument.DUMB_MODE_TRANSITION ->
            SourceReadLimitationWireDocument.DUMB_MODE_TRANSITION
        SourceReadLimitationDocument.SEMANTIC_RESOLUTION_INCOMPLETE ->
            SourceReadLimitationWireDocument.SEMANTIC_RESOLUTION_INCOMPLETE
        SourceReadLimitationDocument.UNSUPPORTED_ENTITY ->
            SourceReadLimitationWireDocument.UNSUPPORTED_ENTITY
        SourceReadLimitationDocument.PROVIDER_FAILURE ->
            SourceReadLimitationWireDocument.PROVIDER_FAILURE
    }

private fun SourceReadLimitationWireDocument.toContract(): SourceReadLimitationDocument =
    when (this) {
        SourceReadLimitationWireDocument.ENTITY_LIMIT_REACHED ->
            SourceReadLimitationDocument.ENTITY_LIMIT_REACHED
        SourceReadLimitationWireDocument.TEXT_BYTE_LIMIT_REACHED ->
            SourceReadLimitationDocument.TEXT_BYTE_LIMIT_REACHED
        SourceReadLimitationWireDocument.WORK_LIMIT_REACHED ->
            SourceReadLimitationDocument.WORK_LIMIT_REACHED
        SourceReadLimitationWireDocument.TIME_LIMIT_REACHED ->
            SourceReadLimitationDocument.TIME_LIMIT_REACHED
        SourceReadLimitationWireDocument.DUMB_MODE_TRANSITION ->
            SourceReadLimitationDocument.DUMB_MODE_TRANSITION
        SourceReadLimitationWireDocument.SEMANTIC_RESOLUTION_INCOMPLETE ->
            SourceReadLimitationDocument.SEMANTIC_RESOLUTION_INCOMPLETE
        SourceReadLimitationWireDocument.UNSUPPORTED_ENTITY ->
            SourceReadLimitationDocument.UNSUPPORTED_ENTITY
        SourceReadLimitationWireDocument.PROVIDER_FAILURE ->
            SourceReadLimitationDocument.PROVIDER_FAILURE
    }

private fun String.protocolText(): WireDocumentConversion<ProtocolText> =
    ProtocolText.parse(this).toWireDocumentConversion()

private fun String.protocolSourceText(): WireDocumentConversion<ProtocolSourceText> =
    ProtocolSourceText.parse(this).toWireDocumentConversion()

private fun Int.protocolOffset(): WireDocumentConversion<ProtocolOffset> =
    ProtocolOffset.parse(this).toWireDocumentConversion()

private fun Int.sourceLineCount() =
    io.github.amichne.kast.protocol.contract.SourceLineCountDocument.parse(this)
        .toWireDocumentConversion()

private fun Int.sourceEntityLimit(): WireDocumentConversion<SourceEntityLimitDocument> =
    SourceEntityLimitDocument.parse(this).toWireDocumentConversion()

private fun Long.sourceTextByteLimit(): WireDocumentConversion<SourceTextByteLimitDocument> =
    SourceTextByteLimitDocument.parse(this).toWireDocumentConversion()

private fun Int.sourceLength(): WireDocumentConversion<SourceLengthDocument> =
    SourceLengthDocument.parse(this).toWireDocumentConversion()

private fun Int.sourceNestingDepth(): WireDocumentConversion<SourceNestingDepthDocument> =
    SourceNestingDepthDocument.parse(this).toWireDocumentConversion()

private fun Int.sourceEntityCount(): WireDocumentConversion<SourceEntityCountDocument> =
    SourceEntityCountDocument.parse(this).toWireDocumentConversion()
