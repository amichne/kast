package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceCoordinateUnitDocument
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourceEntityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceLengthDocument
import io.github.amichne.kast.protocol.contract.SourceNestingDepthDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadContinuationStateDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SourceRegionDocument
import io.github.amichne.kast.protocol.contract.SourceRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionRangeDocument
import io.github.amichne.kast.protocol.contract.SourceSnapshotContextDocument
import io.github.amichne.kast.protocol.contract.SourceSnapshotDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.source.contract.NonEmptySourceRange
import io.github.amichne.kast.source.contract.SourceEntityKind
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadScope
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSelectorTokenCodec
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority

/** Source-owner-issued selectors from one unchanged live authority, with a terminal coverage gap. */
internal class HostedSourcePagingFixture
private constructor(
    val owner: RelationPagingFixture,
    val request: SourceReadRequest,
    val outcome: OperationOutcome.Qualified<SourceReadResult, SourceReadQualification>,
) {
    companion object {
        suspend fun create(): HostedSourcePagingFixture {
            val owner = RelationPagingFixture.live()
            val basis = (owner.page() as OperationOutcome.Qualified).evidence.basis as EvidenceBasis.Live
            val snapshot =
                SourceSnapshot.create(
                    SourceReadContext.Live(owner.authority as LiveSemanticReadAuthority),
                    owner.selector.file as SymbolDiscoveryFileIdentity.Workspace,
                    SourceTextIdentity.fromNormalizedCommittedText("parameters"),
                    Utf16CodeUnitCount.parse(10).sourceFixtureValue(),
                    SourceReadScope.ExactFile,
                )
            val root = SourceSelector.issueRoot(snapshot.range(0, 10), SourceRegionKind.FILE)
            val result =
                SourceReadResult(
                    SourceSnapshotDocument(
                        text(owner.authority.workspaceRoot.value),
                        SourceSnapshotContextDocument.Live(basis.evidence),
                        text("Subject.kt"),
                        text(snapshot.textIdentity.value),
                        SourceCoordinateUnitDocument.UTF16_CODE_UNIT,
                        SourceLengthDocument.parse(10).sourceFixtureValue(),
                    ),
                    SourceRegionDocument(SourceRegionKindDocument.FILE, root.selection()),
                    BoundedProtocolList.create((0 until 6).map { index -> parameter(root, index) })
                        .sourceFixtureValue(),
                    SourceTextProjectionDocument.NotRequested,
                )
            val qualification =
                SourceReadQualification.create(
                        SourceEntityCountDocument.parse(6).sourceFixtureValue(),
                        listOf(SourceReadLimitationDocument.SEMANTIC_RESOLUTION_INCOMPLETE),
                        SourceReadContinuationStateDocument.Unavailable,
                    )
                    .sourceFixtureValue()
            return HostedSourcePagingFixture(
                owner,
                request(root),
                OperationOutcome.Qualified(
                    EvidenceEnvelope(CanonicalOperation.SOURCE_READ.id, basis, result),
                    qualification,
                ),
            )
        }

        private fun request(root: SourceSelector) =
            SourceReadRequest(
                SourceReadAnchorDocument.Source(root.selection().selector),
                SourceRegionSelectionDocument.File,
                SourceEntitySelectionDocument.Matching(
                    SourceContainmentDocument.DESCENDANTS,
                    listOf(SourceEntityFilterDocument.Parameters),
                ),
                SourceTextRequestDocument.None,
                SourceEntityLimitDocument.parse(6).sourceFixtureValue(),
                SourceTextByteLimitDocument.parse(50_000).sourceFixtureValue(),
                SourceReadPageDocument.First,
            )

        private fun parameter(parent: SourceSelector, index: Int): SourceEntityDocument {
            val selector =
                SourceSelector.issueEntity(
                        parent,
                        NonEmptySourceRange.create(parent.snapshot.range(index, index + 1)).sourceFixtureValue(),
                        SourceEntityKind.VALUE_PARAMETER,
                        SourceEntityName.present("p$index").sourceFixtureValue(),
                    )
                    .sourceFixtureValue()
            return SourceEntityDocument.ValueParameter(
                text("p$index"),
                SourceNestingDepthDocument.parse(1).sourceFixtureValue(),
                parent.selection().selector,
                selector.selection(),
            )
        }

        private fun SourceSnapshot.range(start: Int, end: Int) =
            SourceRange.create(
                    this,
                    Utf16CodeUnitOffset.parse(start).sourceFixtureValue(),
                    Utf16CodeUnitOffset.parse(end).sourceFixtureValue(),
                )
                .sourceFixtureValue()

        private fun SourceSelector.selection() =
            SourceSelectionDocument(
                text(SourceSelectorTokenCodec.encode(this).value),
                SourceSelectionRangeDocument.create(
                        ProtocolOffset.parse(range.startInclusive.value).sourceFixtureValue(),
                        ProtocolOffset.parse(range.endExclusive.value).sourceFixtureValue(),
                    )
                    .sourceFixtureValue(),
            )

        private fun text(value: String) = ProtocolText.parse(value).sourceFixtureValue()
    }
}

internal fun <T> Refinement<T, *>.sourceFixtureValue(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Source fixture rejected: $failure")
    }
