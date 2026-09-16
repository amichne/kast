package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceCoordinateUnitDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationSemanticIdentityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourceEntityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntityTargetDocument
import io.github.amichne.kast.protocol.contract.SourceLengthDocument
import io.github.amichne.kast.protocol.contract.SourceNestingDepthDocument
import io.github.amichne.kast.protocol.contract.SourceReadFailure
import io.github.amichne.kast.protocol.contract.SourceReadFormatDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SourceRegionDocument
import io.github.amichne.kast.protocol.contract.SourceRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionRangeDocument
import io.github.amichne.kast.protocol.contract.SourceSnapshotContextDocument
import io.github.amichne.kast.protocol.contract.SourceSnapshotDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextWithheldReasonDocument
import io.github.amichne.kast.protocol.contract.SourceUnresolvedReasonDocument
import io.github.amichne.kast.protocol.contract.reason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

internal object CanonicalSourceReadSerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)

    val request = factory.create(SourceReadRequest.serializer())
    val result =
        WireValueCodec<SourceReadResult>(
            encodeValue = { value ->
                when (value.format) {
                    SourceReadFormatDocument.EXPANDED ->
                        wireJson.encodeToJsonElement(SourceReadResultWireDocument.serializer(), value.toWireDocument())
                    SourceReadFormatDocument.COMPACT ->
                        wireJson.encodeToJsonElement(
                            CompactSourceReadDocument.serializer(),
                            value.compactSourceDocument(),
                        )
                }
            },
            decodeValue = { element ->
                if (element is JsonObject && "format" in element)
                    wireJson.decodeFromJsonElement(CompactSourceReadDocument.serializer(), element).expand()
                else wireJson.decodeFromJsonElement(SourceReadResultWireDocument.serializer(), element).toContract()
            },
        )
    val qualification =
        factory.create(
            SourceReadQualificationWireDocument.serializer(),
            SourceReadQualification::toWireDocument,
            SourceReadQualificationWireDocument::toContract,
        )
    val rejection: WireValueCodec<SourceReadFailure> =
        factory.create(
            io.github.amichne.kast.protocol.contract.SourceReadCause.serializer(),
            { value: SourceReadFailure -> value.reason() },
            { WireDocumentConversion.Converted(it) },
        )
}

internal fun SourceReadResult.toWireDocument(): SourceReadResultWireDocument =
    SourceReadResultWireDocument(
        snapshot.toWireDocument(),
        region.toWireDocument(),
        entities.values.map(SourceEntityDocument::toWireDocument),
        text.toWireDocument(),
        executionBudget,
        referenceAcquisitions = referenceAcquisitions,
    )

internal fun SourceReadResultWireDocument.toContract(): WireDocumentConversion<SourceReadResult> =
    snapshot.toContract().flatMapConverted { admittedSnapshot ->
        region.toContract().flatMapConverted { admittedRegion ->
            entities.convertEach(SourceEntityWireDocument::toContract).flatMapConverted { admittedEntities ->
                BoundedProtocolList.create(admittedEntities).toWireDocumentConversion().flatMapConverted {
                    boundedEntities ->
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
                                    executionBudget,
                                    referenceAcquisitions = referenceAcquisitions,
                                )
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
    fun SourceSelectionDocument.isWithinSnapshot(): Boolean = range.endExclusive.value <= snapshotLength
    if (!region.selection.isWithinSnapshot()) return false
    if (entities.any { !it.selection.isWithinSnapshot() }) return false
    return when (text) {
        SourceTextProjectionDocument.NotRequested,
        is SourceTextProjectionDocument.Withheld -> true
        is SourceTextProjectionDocument.Returned ->
            text.selection.isWithinSnapshot() &&
                text.text.value.length ==
                    text.selection.range.endExclusive.value - text.selection.range.startInclusive.value &&
                text.lines.startInclusive.value <= text.selection.range.startInclusive.value.toLong() + 1 &&
                text.lines.endInclusive.value <= snapshotLength.toLong() + 1 &&
                text.lines.endInclusive.value - text.lines.startInclusive.value ==
                    text.text.value.dropLast(1).count { it == '\n' }.toLong()
    }
}

private fun SourceSnapshotDocument.toWireDocument(): SourceSnapshotWireDocument =
    when (val value = context) {
        is SourceSnapshotContextDocument.Published ->
            SourceSnapshotWireDocument(
                canonicalRoot.value,
                value.generation.value,
                value.sourceState.value,
                file.value,
                textIdentity.value,
                coordinateUnit.toWireDocument(),
                length.value,
            )
        is SourceSnapshotContextDocument.Live ->
            SourceSnapshotWireDocument(
                canonicalRoot = canonicalRoot.value,
                file = file.value,
                textIdentity = textIdentity.value,
                coordinateUnit = coordinateUnit.toWireDocument(),
                length = length.value,
                live = value.evidence.document(),
            )
    }

private fun SourceSnapshotWireDocument.toContract(): WireDocumentConversion<SourceSnapshotDocument> {
    val context =
        when {
            generation != null && sourceState != null && live == null -> {
                val admittedGeneration =
                    when (val parsed = EvidenceGeneration.parse(generation)) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected -> return WireDocumentConversion.Rejected
                    }
                val admittedState =
                    when (val parsed = ProtocolText.parse(sourceState)) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected -> return WireDocumentConversion.Rejected
                    }
                SourceSnapshotContextDocument.Published(admittedGeneration, admittedState)
            }
            generation == null && sourceState == null && live != null -> {
                val evidence =
                    when (val parsed = live.admit()) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected -> return WireDocumentConversion.Rejected
                    }
                if (evidence.workspaceRoot != canonicalRoot) return WireDocumentConversion.Rejected
                SourceSnapshotContextDocument.Live(evidence)
            }
            else -> return WireDocumentConversion.Rejected
        }
    return canonicalRoot.protocolText().flatMapConverted { admittedRoot ->
        file.protocolText().flatMapConverted { admittedFile ->
            textIdentity.protocolText().flatMapConverted { admittedIdentity ->
                length.sourceLength().mapConverted { admittedLength ->
                    SourceSnapshotDocument(
                        admittedRoot,
                        context,
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

private fun SourceSelectionDocument.toWireDocument(): SourceSelectionWireDocument =
    SourceSelectionWireDocument(selector.value, range.toWireDocument())

private fun SourceSelectionWireDocument.toContract(): WireDocumentConversion<SourceSelectionDocument> =
    combineConverted(
        selector.protocolText(),
        range.toContract(),
        ::SourceSelectionDocument,
    )

private fun SourceSelectionRangeDocument.toWireDocument(): SourceSelectionRangeWireDocument =
    SourceSelectionRangeWireDocument(startInclusive.value, endExclusive.value)

private fun SourceSelectionRangeWireDocument.toContract(): WireDocumentConversion<SourceSelectionRangeDocument> =
    combineConverted(
            startInclusive.protocolOffset(),
            endExclusive.protocolOffset(),
        ) { start, end ->
            SourceSelectionRangeDocument.create(start, end).toWireDocumentConversion()
        }
        .flattenConverted()

private fun SourceRegionDocument.toWireDocument(): SourceRegionWireDocument =
    SourceRegionWireDocument(kind.toWireDocument(), selection.toWireDocument())

private fun SourceRegionWireDocument.toContract(): WireDocumentConversion<SourceRegionDocument> =
    selection.toContract().mapConverted { SourceRegionDocument(kind.toContract(), it) }

private fun SourceEntityDocument.toWireDocument(): SourceEntityWireDocument =
    when (this) {
        is SourceEntityDocument.Declaration ->
            SourceEntityWireDocument.Declaration(
                kind,
                name.value,
                visibility,
                nestingDepth.value,
                parentSelector.value,
                selection.toWireDocument(),
                semanticIdentity.toWireDocument(),
            )
        is SourceEntityDocument.ValueParameter ->
            SourceEntityWireDocument.ValueParameter(
                name.value,
                nestingDepth.value,
                parentSelector.value,
                selection.toWireDocument(),
            )
        is SourceEntityDocument.Call ->
            SourceEntityWireDocument.Call(
                nestingDepth.value,
                parentSelector.value,
                selection.toWireDocument(),
                callee.toWireDocument(),
                target.toWireDocument(),
            )
        is SourceEntityDocument.Reference ->
            SourceEntityWireDocument.Reference(
                name.value,
                nestingDepth.value,
                parentSelector.value,
                selection.toWireDocument(),
                target.toWireDocument(),
            )
    }

private fun SourceEntityWireDocument.toContract(): WireDocumentConversion<SourceEntityDocument> =
    when (this) {
        is SourceEntityWireDocument.Declaration ->
            name.protocolText().flatMapConverted { admittedName ->
                nestingDepth.sourceNestingDepth().flatMapConverted { admittedDepth ->
                    parentSelector.protocolText().flatMapConverted { admittedParent ->
                        selection.toContract().flatMapConverted { admittedSelection ->
                            semanticIdentity.toContract().mapConverted { admittedIdentity ->
                                SourceEntityDocument.Declaration(
                                    kind,
                                    admittedName,
                                    visibility,
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
        is SourceEntityWireDocument.ValueParameter ->
            name.protocolText().flatMapConverted { admittedName ->
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
        is SourceEntityWireDocument.Call ->
            nestingDepth.sourceNestingDepth().flatMapConverted { admittedDepth ->
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
        is SourceEntityWireDocument.Reference ->
            name.protocolText().flatMapConverted { admittedName ->
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

private fun SourceDeclarationSemanticIdentityDocument.toWireDocument(): SourceDeclarationSemanticIdentityWireDocument =
    when (this) {
        is SourceDeclarationSemanticIdentityDocument.Candidate ->
            SourceDeclarationSemanticIdentityWireDocument.Candidate(selector.value)
    }

private fun SourceDeclarationSemanticIdentityWireDocument.toContract():
    WireDocumentConversion<SourceDeclarationSemanticIdentityDocument> =
    when (this) {
        is SourceDeclarationSemanticIdentityWireDocument.Candidate ->
            selector.protocolText().mapConverted(SourceDeclarationSemanticIdentityDocument::Candidate)
    }

private fun SourceEntityTargetDocument.toWireDocument(): SourceEntityTargetWireDocument =
    when (this) {
        is SourceEntityTargetDocument.Candidate -> SourceEntityTargetWireDocument.Candidate(selector.value)
        is SourceEntityTargetDocument.Local -> SourceEntityTargetWireDocument.Local(selector.value)
        is SourceEntityTargetDocument.Unresolved -> SourceEntityTargetWireDocument.Unresolved(reason.toWireDocument())
    }

private fun SourceEntityTargetWireDocument.toContract(): WireDocumentConversion<SourceEntityTargetDocument> =
    when (this) {
        is SourceEntityTargetWireDocument.Candidate ->
            selector.protocolText().mapConverted(SourceEntityTargetDocument::Candidate)
        is SourceEntityTargetWireDocument.Local ->
            selector.protocolText().mapConverted(SourceEntityTargetDocument::Local)
        is SourceEntityTargetWireDocument.Unresolved ->
            WireDocumentConversion.Converted(SourceEntityTargetDocument.Unresolved(reason.toContract()))
    }

private fun SourceTextProjectionDocument.toWireDocument(): SourceTextProjectionWireDocument =
    when (this) {
        SourceTextProjectionDocument.NotRequested -> SourceTextProjectionWireDocument.NotRequested
        is SourceTextProjectionDocument.Returned ->
            SourceTextProjectionWireDocument.Returned(
                selection.toWireDocument(),
                text.value,
                SourceLineRangeWireDocument(lines.startInclusive.value, lines.endInclusive.value),
            )
        is SourceTextProjectionDocument.Withheld -> SourceTextProjectionWireDocument.Withheld(reason.toWireDocument())
    }

private fun SourceTextProjectionWireDocument.toContract(): WireDocumentConversion<SourceTextProjectionDocument> =
    when (this) {
        SourceTextProjectionWireDocument.NotRequested ->
            WireDocumentConversion.Converted(SourceTextProjectionDocument.NotRequested)
        is SourceTextProjectionWireDocument.Returned ->
            combineConverted(
                selection.toContract(),
                text.protocolSourceText(),
                io.github.amichne.kast.protocol.contract.SourceLineRangeDocument.parse(
                        lines.startInclusive,
                        lines.endInclusive,
                    )
                    .toWireDocumentConversion(),
                SourceTextProjectionDocument::Returned,
            )
        is SourceTextProjectionWireDocument.Withheld ->
            WireDocumentConversion.Converted(SourceTextProjectionDocument.Withheld(reason.toContract()))
    }

private fun SourceReadQualification.toWireDocument(): SourceReadQualificationWireDocument =
    SourceReadQualificationWireDocument(
        knownMinimumEntityCount.value,
        limitations.map(SourceReadLimitationDocument::toWireDocument),
        progress,
    )

private fun SourceReadQualificationWireDocument.toContract(): WireDocumentConversion<SourceReadQualification> =
    knownMinimumEntityCount.sourceEntityCount().flatMapConverted { admittedCount ->
        SourceReadQualification.create(
                admittedCount,
                limitations.map(SourceReadLimitationWireDocument::toContract),
                progress,
            )
            .toWireDocumentConversion()
    }

private fun SourceCoordinateUnitDocument.toWireDocument(): SourceCoordinateUnitWireDocument =
    when (this) {
        SourceCoordinateUnitDocument.UTF16_CODE_UNIT -> SourceCoordinateUnitWireDocument.UTF16_CODE_UNIT
    }

private fun SourceCoordinateUnitWireDocument.toContract(): SourceCoordinateUnitDocument =
    when (this) {
        SourceCoordinateUnitWireDocument.UTF16_CODE_UNIT -> SourceCoordinateUnitDocument.UTF16_CODE_UNIT
    }

private fun SourceRegionKindDocument.toWireDocument(): SourceRegionKindWireDocument =
    when (this) {
        SourceRegionKindDocument.ANCHOR -> SourceRegionKindWireDocument.ANCHOR
        SourceRegionKindDocument.DECLARATION -> SourceRegionKindWireDocument.DECLARATION
        SourceRegionKindDocument.CALLABLE_BODY -> SourceRegionKindWireDocument.CALLABLE_BODY
        SourceRegionKindDocument.CLASS_BODY -> SourceRegionKindWireDocument.CLASS_BODY
        SourceRegionKindDocument.FILE -> SourceRegionKindWireDocument.FILE
        SourceRegionKindDocument.WINDOW -> SourceRegionKindWireDocument.WINDOW
    }

private fun SourceRegionKindWireDocument.toContract(): SourceRegionKindDocument =
    when (this) {
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
        SourceUnresolvedReasonDocument.UNSUPPORTED_TARGET -> SourceUnresolvedReasonWireDocument.UNSUPPORTED_TARGET
    }

private fun SourceUnresolvedReasonWireDocument.toContract(): SourceUnresolvedReasonDocument =
    when (this) {
        SourceUnresolvedReasonWireDocument.NAME_NOT_FOUND -> SourceUnresolvedReasonDocument.NAME_NOT_FOUND
        SourceUnresolvedReasonWireDocument.AMBIGUOUS -> SourceUnresolvedReasonDocument.AMBIGUOUS
        SourceUnresolvedReasonWireDocument.ERROR_TYPE -> SourceUnresolvedReasonDocument.ERROR_TYPE
        SourceUnresolvedReasonWireDocument.UNSUPPORTED_TARGET -> SourceUnresolvedReasonDocument.UNSUPPORTED_TARGET
    }

private fun SourceTextWithheldReasonDocument.toWireDocument(): SourceTextWithheldReasonWireDocument =
    when (this) {
        SourceTextWithheldReasonDocument.BYTE_LIMIT_REACHED -> SourceTextWithheldReasonWireDocument.BYTE_LIMIT_REACHED
        SourceTextWithheldReasonDocument.PROVIDER_UNAVAILABLE ->
            SourceTextWithheldReasonWireDocument.PROVIDER_UNAVAILABLE
    }

private fun SourceTextWithheldReasonWireDocument.toContract(): SourceTextWithheldReasonDocument =
    when (this) {
        SourceTextWithheldReasonWireDocument.BYTE_LIMIT_REACHED -> SourceTextWithheldReasonDocument.BYTE_LIMIT_REACHED
        SourceTextWithheldReasonWireDocument.PROVIDER_UNAVAILABLE ->
            SourceTextWithheldReasonDocument.PROVIDER_UNAVAILABLE
    }

private fun String.protocolText(): WireDocumentConversion<ProtocolText> =
    ProtocolText.parse(this).toWireDocumentConversion()

private fun String.protocolSourceText(): WireDocumentConversion<ProtocolSourceText> =
    ProtocolSourceText.parse(this).toWireDocumentConversion()

private fun Int.protocolOffset(): WireDocumentConversion<ProtocolOffset> =
    ProtocolOffset.parse(this).toWireDocumentConversion()

private fun Int.sourceLineCount() =
    io.github.amichne.kast.protocol.contract.SourceLineCountDocument.parse(this).toWireDocumentConversion()

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
