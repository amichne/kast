package io.github.amichne.kast.evidence.sqlite.historical.cli

import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverage
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclaration
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclarationId
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalFile
import io.github.amichne.kast.topology.contract.historical.HistoricalFileProvenance
import io.github.amichne.kast.topology.contract.historical.HistoricalLimitation
import io.github.amichne.kast.topology.contract.historical.HistoricalModule
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrenceOwner
import io.github.amichne.kast.topology.contract.historical.HistoricalOpenWork
import io.github.amichne.kast.topology.contract.historical.HistoricalOwnershipUnavailableCause
import io.github.amichne.kast.topology.contract.historical.HistoricalPageEnd
import io.github.amichne.kast.topology.contract.historical.HistoricalPageWork
import io.github.amichne.kast.topology.contract.historical.HistoricalProfile
import io.github.amichne.kast.topology.contract.historical.HistoricalReferenceContext
import io.github.amichne.kast.topology.contract.historical.HistoricalReferenceKind
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInventory
import io.github.amichne.kast.topology.contract.historical.HistoricalSourceSet
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageEnd
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageRow

internal fun HistoricalSnapshotInventory.document(): ManifestDocument =
    ManifestDocument(
        identity.value,
        header.revision.repository.value,
        header.revision.commit.value,
        header.revision.tree.value,
        header.context.model.value,
        header.context.resolution.value,
        header.context.compiler.value,
        header.producer.value,
        when (header.profile) {
            HistoricalProfile.STRUCTURE_V1 -> OfflineProfile.STRUCTURE_V1
            HistoricalProfile.REFERENCES_V1 -> OfflineProfile.REFERENCES_V1
        },
        modules.size,
        coverageSummary(),
    )

private fun HistoricalSnapshotInventory.coverageSummary(): List<FamilyCoverageDocument> {
    val grouped = coverage.groupBy { it.family }
    return HistoricalFactFamily.entries.map { family ->
        val statuses = grouped[family].orEmpty().map { it.status }
        val causes =
            statuses
                .flatMap { status ->
                    when (status) {
                        HistoricalCoverageStatus.Complete,
                        HistoricalCoverageStatus.NotCollected -> emptyList()
                        is HistoricalCoverageStatus.OutsideProfile -> listOf(status.reason)
                        is HistoricalCoverageStatus.Qualified -> status.reasons.toList()
                    }
                }
                .distinct()
                .sorted()
                .map { it.document() }
        FamilyCoverageDocument(
            family.document(),
            statuses.count { it == HistoricalCoverageStatus.Complete },
            statuses.count { it is HistoricalCoverageStatus.Qualified },
            statuses.count { it == HistoricalCoverageStatus.NotCollected },
            statuses.count { it is HistoricalCoverageStatus.OutsideProfile },
            causes,
        )
    }
}

internal fun HistoricalDeclarationId.document(): DeclarationIdentityDocument =
    DeclarationIdentityDocument(compiler.value, file.value, range.startInclusive, range.endExclusive)

internal fun HistoricalDeclaration.document(): DeclarationDocument =
    DeclarationDocument(
        id.document(),
        name.value,
        signature.value,
        when (kind) {
            CompilerSymbolKind.CLASSLIKE -> DeclarationKindDocument.CLASSLIKE
            CompilerSymbolKind.CONSTRUCTOR -> DeclarationKindDocument.CONSTRUCTOR
            CompilerSymbolKind.FUNCTION -> DeclarationKindDocument.FUNCTION
            CompilerSymbolKind.PROPERTY -> DeclarationKindDocument.PROPERTY
            CompilerSymbolKind.TYPE_ALIAS -> DeclarationKindDocument.TYPE_ALIAS
        },
    )

internal fun HistoricalModule.document(): ModuleDocument =
    ModuleDocument(
        id.value,
        project.value,
        name.value,
        when (val source = sourceSet) {
            is HistoricalSourceSet.Named -> source.name.value
            HistoricalSourceSet.Rootless -> null
        },
    )

internal fun HistoricalFile.document(): FileDocument =
    FileDocument(
        path.value,
        content.value,
        module.value,
        when (val source = provenance) {
            is HistoricalFileProvenance.Tracked -> ProvenanceDocument.Tracked(source.blob.value)
            is HistoricalFileProvenance.Generated -> ProvenanceDocument.Generated(source.producer.value)
        },
    )

internal fun HistoricalCoverage.document(): CoverageDocument =
    CoverageDocument(
        module.value,
        family.document(),
        when (status) {
            HistoricalCoverageStatus.Complete -> OfflineCoverageStatus.COMPLETE
            HistoricalCoverageStatus.NotCollected -> OfflineCoverageStatus.NOT_COLLECTED
            is HistoricalCoverageStatus.OutsideProfile -> OfflineCoverageStatus.OUTSIDE_PROFILE
            is HistoricalCoverageStatus.Qualified -> OfflineCoverageStatus.QUALIFIED
        },
        when (val value = status) {
            HistoricalCoverageStatus.Complete,
            HistoricalCoverageStatus.NotCollected -> emptyList()
            is HistoricalCoverageStatus.OutsideProfile -> listOf(value.reason.document())
            is HistoricalCoverageStatus.Qualified -> value.reasons.map { it.document() }
        },
    )

internal fun HistoricalFactFamily.document(): OfflineFamily =
    when (this) {
        HistoricalFactFamily.MODULES -> OfflineFamily.MODULES
        HistoricalFactFamily.FILES -> OfflineFamily.FILES
        HistoricalFactFamily.DECLARATIONS -> OfflineFamily.DECLARATIONS
        HistoricalFactFamily.REFERENCES -> OfflineFamily.REFERENCES
    }

private fun HistoricalLimitation.document(): OfflineLimitation =
    when (this) {
        HistoricalLimitation.UNSUPPORTED_DECLARATION -> OfflineLimitation.UNSUPPORTED_DECLARATION
        HistoricalLimitation.UNRESOLVED_TARGET -> OfflineLimitation.UNRESOLVED_TARGET
        HistoricalLimitation.EXTERNAL_TARGET -> OfflineLimitation.EXTERNAL_TARGET
        HistoricalLimitation.UNSUPPORTED_TARGET -> OfflineLimitation.UNSUPPORTED_TARGET
        HistoricalLimitation.OWNER_UNAVAILABLE -> OfflineLimitation.OWNER_UNAVAILABLE
        HistoricalLimitation.MODEL_INCOMPLETE -> OfflineLimitation.MODEL_INCOMPLETE
        HistoricalLimitation.ROOTLESS -> OfflineLimitation.ROOTLESS
        HistoricalLimitation.GENERATED_INPUT -> OfflineLimitation.GENERATED_INPUT
        HistoricalLimitation.RESOURCE_LIMIT -> OfflineLimitation.RESOURCE_LIMIT
    }

internal fun HistoricalUsageRow.document(): UsageRowDocument =
    UsageRowDocument(
        ordinal.value,
        sourceModule.value,
        targetModule.value,
        occurrence.sourceFile.value,
        occurrence.range.startInclusive,
        occurrence.range.endExclusive,
        occurrence.target.document(),
        when (val owner = occurrence.owner) {
            is HistoricalOccurrenceOwner.Declaration -> OwnerDocument.Declaration(owner.id.document())
            is HistoricalOccurrenceOwner.FileScoped -> OwnerDocument.File(owner.context.document())
            is HistoricalOccurrenceOwner.Unavailable ->
                OwnerDocument.Unavailable(
                    when (owner.cause) {
                        HistoricalOwnershipUnavailableCause.UNSUPPORTED_DECLARATION ->
                            OwnershipUnavailableDocument.UNSUPPORTED_DECLARATION
                        HistoricalOwnershipUnavailableCause.UNRESOLVED_DECLARATION ->
                            OwnershipUnavailableDocument.UNRESOLVED_DECLARATION
                    }
                )
        },
        when (occurrence.kind) {
            HistoricalReferenceKind.REFERENCE -> ReferenceKindDocument.REFERENCE
            HistoricalReferenceKind.CALL -> ReferenceKindDocument.CALL
            HistoricalReferenceKind.TYPE_USE -> ReferenceKindDocument.TYPE_USE
            HistoricalReferenceKind.IMPORT -> ReferenceKindDocument.IMPORT
            HistoricalReferenceKind.INHERITANCE -> ReferenceKindDocument.INHERITANCE
            HistoricalReferenceKind.OVERRIDE -> ReferenceKindDocument.OVERRIDE
        },
        occurrence.context.document(),
    )

private fun HistoricalReferenceContext.document(): ReferenceContextDocument =
    when (this) {
        HistoricalReferenceContext.IMPORT -> ReferenceContextDocument.IMPORT
        HistoricalReferenceContext.ALIASED_IMPORT -> ReferenceContextDocument.ALIASED_IMPORT
        HistoricalReferenceContext.TYPE -> ReferenceContextDocument.TYPE
        HistoricalReferenceContext.CODE -> ReferenceContextDocument.CODE
        HistoricalReferenceContext.FILE_ANNOTATION -> ReferenceContextDocument.FILE_ANNOTATION
    }

internal fun HistoricalPageEnd.document(): RowCursorDocument? =
    when (this) {
        HistoricalPageEnd.Exhausted -> null
        is HistoricalPageEnd.More ->
            RowCursorDocument(position.snapshot.value, position.family.document(), position.ordinal.value)
    }

internal fun HistoricalUsageEnd.document(): UsageCursorDocument? =
    when (this) {
        HistoricalUsageEnd.Exhausted -> null
        is HistoricalUsageEnd.More -> UsageCursorDocument(cursor.snapshot.value, cursor.query.value, cursor.after.value)
    }

internal fun workDocument(open: HistoricalOpenWork, page: HistoricalPageWork, selections: Long = 0): ReadWorkDocument =
    ReadWorkDocument(
        open.rowsExamined.value,
        open.artifactBytes.value,
        open.copiedBytesRead.value,
        open.copiedBytesWritten.value,
        selections,
        page.rowsExamined.value,
        page.rowsReturned.value,
    )
