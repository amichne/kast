@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint
import io.github.amichne.kast.protocol.contract.SourceDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationVisibilityDocument
import io.github.amichne.kast.protocol.contract.SourceReadFormatDocument
import io.github.amichne.kast.protocol.contract.SourceReadResult
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Serialized presentation only: table indices are response-local joins, never input capabilities. */
@Serializable
data class CompactSourceReadDocument(
    val format: SourceReadFormatDocument = SourceReadFormatDocument.COMPACT,
    val snapshot: CompactSourceSnapshotDocument,
    val region: CompactSourceRegionDocument,
    val text: CompactSourceTextDocument,
    @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
    val selections: List<CompactSourceSelectionEntry>,
    val entities: List<CompactSourceEntityDocument>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: ExecutionBudgetReport? = null,
)

@Serializable
data class CompactSourceSnapshotDocument(
    val canonicalRoot: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val generation: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sourceState: String? = null,
    val file: String,
    val textIdentity: String,
    val coordinateUnit: SourceCoordinateUnitWireDocument,
    @ProtocolIntegerConstraint(minimum = 0) val length: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val live: CompactSourceLiveDocument? = null,
)

@Serializable
data class CompactSourceLiveDocument(
    val root: String,
    val host: String,
    val epoch: Long,
    val contentView: String,
    val version: Int,
)

@Serializable data class CompactSourceSelectionEntry(val selector: String)

@Serializable
data class CompactSourceRangeDocument(
    @ProtocolIntegerConstraint(minimum = 0) val startInclusive: Int,
    @ProtocolIntegerConstraint(minimum = 0) val endExclusive: Int,
)

@Serializable
data class CompactSourceSelectionDocument(
    @ProtocolIntegerConstraint(minimum = 0) val id: Int,
    val range: CompactSourceRangeDocument,
)

@Serializable
data class CompactSourceRegionDocument(
    val kind: SourceRegionKindWireDocument,
    val selection: CompactSourceSelectionDocument,
)

@Serializable data class CompactSourceLinesDocument(val startInclusive: Long, val endInclusive: Long)

@Serializable
sealed interface CompactSourceTextDocument {
    @Serializable @SerialName("not-requested") data object NotRequested : CompactSourceTextDocument

    @Serializable
    @SerialName("returned")
    data class Returned(
        val text: String,
        val selection: CompactSourceSelectionDocument,
        val lines: CompactSourceLinesDocument,
    ) : CompactSourceTextDocument

    @Serializable
    @SerialName("withheld")
    data class Withheld(val reason: SourceTextWithheldReasonWireDocument) : CompactSourceTextDocument
}

@Serializable
sealed interface CompactSourceTargetDocument {
    @Serializable @SerialName("candidate") data class Candidate(val selector: String) : CompactSourceTargetDocument

    @Serializable
    @SerialName("local")
    data class Local(@ProtocolIntegerConstraint(minimum = 0) val selection: Int) : CompactSourceTargetDocument

    @Serializable
    @SerialName("unresolved")
    data class Unresolved(val reason: SourceUnresolvedReasonWireDocument) : CompactSourceTargetDocument
}

@Serializable
sealed interface CompactSourceEntityDocument {
    @Serializable
    @SerialName("declaration")
    data class Declaration(
        val kind: SourceDeclarationKindDocument,
        val name: String,
        val visibility: SourceDeclarationVisibilityDocument,
        @ProtocolIntegerConstraint(minimum = 0) val nestingDepth: Int,
        @ProtocolIntegerConstraint(minimum = 0) val parent: Int,
        val selection: CompactSourceSelectionDocument,
        val target: CompactSourceTargetDocument.Candidate,
    ) : CompactSourceEntityDocument

    @Serializable
    @SerialName("value-parameter")
    data class ValueParameter(
        val name: String,
        @ProtocolIntegerConstraint(minimum = 0) val nestingDepth: Int,
        @ProtocolIntegerConstraint(minimum = 0) val parent: Int,
        val selection: CompactSourceSelectionDocument,
    ) : CompactSourceEntityDocument

    @Serializable
    @SerialName("call")
    data class Call(
        @ProtocolIntegerConstraint(minimum = 0) val nestingDepth: Int,
        @ProtocolIntegerConstraint(minimum = 0) val parent: Int,
        val selection: CompactSourceSelectionDocument,
        val callee: CompactSourceSelectionDocument,
        val target: CompactSourceTargetDocument,
    ) : CompactSourceEntityDocument

    @Serializable
    @SerialName("reference")
    data class Reference(
        val name: String,
        @ProtocolIntegerConstraint(minimum = 0) val nestingDepth: Int,
        @ProtocolIntegerConstraint(minimum = 0) val parent: Int,
        val selection: CompactSourceSelectionDocument,
        val target: CompactSourceTargetDocument,
    ) : CompactSourceEntityDocument
}

/** One projection over the already admitted canonical result; no selector issuance or restoration occurs here. */
fun SourceReadResult.compactSourceDocument(): CompactSourceReadDocument = toWireDocument().compact()

internal fun SourceReadResultWireDocument.compact(): CompactSourceReadDocument = SourceCompaction().project(this)

private class SourceCompaction {
    val table = linkedMapOf<String, Int>()

    fun id(selector: String): Int = table.getOrPut(selector) { table.size }

    fun selection(value: SourceSelectionWireDocument) =
        CompactSourceSelectionDocument(
            id(value.selector),
            CompactSourceRangeDocument(value.range.startInclusive, value.range.endExclusive),
        )

    fun target(value: SourceEntityTargetWireDocument): CompactSourceTargetDocument =
        when (value) {
            is SourceEntityTargetWireDocument.Candidate -> CompactSourceTargetDocument.Candidate(value.selector)
            is SourceEntityTargetWireDocument.Local -> CompactSourceTargetDocument.Local(id(value.selector))
            is SourceEntityTargetWireDocument.Unresolved -> CompactSourceTargetDocument.Unresolved(value.reason)
        }

    private fun text(value: SourceTextProjectionWireDocument): CompactSourceTextDocument =
        when (value) {
            SourceTextProjectionWireDocument.NotRequested -> CompactSourceTextDocument.NotRequested
            is SourceTextProjectionWireDocument.Returned ->
                CompactSourceTextDocument.Returned(
                    value.text,
                    selection(value.selection),
                    CompactSourceLinesDocument(value.lines.startInclusive, value.lines.endInclusive),
                )
            is SourceTextProjectionWireDocument.Withheld -> CompactSourceTextDocument.Withheld(value.reason)
        }

    private fun entity(value: SourceEntityWireDocument): CompactSourceEntityDocument =
        when (value) {
            is SourceEntityWireDocument.Declaration ->
                CompactSourceEntityDocument.Declaration(
                    value.kind,
                    value.name,
                    value.visibility,
                    value.nestingDepth,
                    id(value.parentSelector),
                    selection(value.selection),
                    CompactSourceTargetDocument.Candidate(
                        (value.semanticIdentity as SourceDeclarationSemanticIdentityWireDocument.Candidate).selector
                    ),
                )
            is SourceEntityWireDocument.ValueParameter ->
                CompactSourceEntityDocument.ValueParameter(
                    value.name,
                    value.nestingDepth,
                    id(value.parentSelector),
                    selection(value.selection),
                )
            is SourceEntityWireDocument.Call ->
                CompactSourceEntityDocument.Call(
                    value.nestingDepth,
                    id(value.parentSelector),
                    selection(value.selection),
                    selection(value.callee),
                    target(value.target),
                )
            is SourceEntityWireDocument.Reference ->
                CompactSourceEntityDocument.Reference(
                    value.name,
                    value.nestingDepth,
                    id(value.parentSelector),
                    selection(value.selection),
                    target(value.target),
                )
        }

    fun project(value: SourceReadResultWireDocument): CompactSourceReadDocument =
        with(value) {
            val compactRegion = CompactSourceRegionDocument(region.kind, selection(region.selection))
            val compactText = text(text)
            val rows = entities.map(::entity)
            CompactSourceReadDocument(
                snapshot =
                    CompactSourceSnapshotDocument(
                        snapshot.canonicalRoot,
                        snapshot.generation,
                        snapshot.sourceState,
                        snapshot.file,
                        snapshot.textIdentity,
                        SourceCoordinateUnitWireDocument.UTF16_CODE_UNIT,
                        snapshot.length,
                        snapshot.live?.let {
                            CompactSourceLiveDocument(it.root, it.host, it.epoch, it.contentView, it.version)
                        },
                    ),
                region = compactRegion,
                text = compactText,
                selections = table.keys.map(::CompactSourceSelectionEntry),
                entities = rows,
                executionBudget = executionBudget,
            )
        }
}

internal fun CompactSourceReadDocument.expand(): WireDocumentConversion<SourceReadResult> {
    if (format != SourceReadFormatDocument.COMPACT) return WireDocumentConversion.Rejected
    if (
        selections.isEmpty() ||
            selections.distinct() != selections ||
            snapshot.coordinateUnit != SourceCoordinateUnitWireDocument.UTF16_CODE_UNIT
    )
        return WireDocumentConversion.Rejected
    val expanded = SourceExpansion(this).project() ?: return WireDocumentConversion.Rejected
    if (expanded.compact() != this) return WireDocumentConversion.Rejected
    return expanded.toContract().mapConverted { it.copy(format = SourceReadFormatDocument.COMPACT) }
}

private class SourceExpansion(private val document: CompactSourceReadDocument) {
    fun token(id: Int): String? = document.selections.getOrNull(id)?.selector

    fun selection(value: CompactSourceSelectionDocument): SourceSelectionWireDocument? =
        token(value.id)?.let {
            SourceSelectionWireDocument(
                it,
                SourceSelectionRangeWireDocument(value.range.startInclusive, value.range.endExclusive),
            )
        }

    fun target(value: CompactSourceTargetDocument): SourceEntityTargetWireDocument? =
        when (value) {
            is CompactSourceTargetDocument.Candidate -> SourceEntityTargetWireDocument.Candidate(value.selector)
            is CompactSourceTargetDocument.Local -> token(value.selection)?.let(SourceEntityTargetWireDocument::Local)
            is CompactSourceTargetDocument.Unresolved -> SourceEntityTargetWireDocument.Unresolved(value.reason)
        }

    private fun text(value: CompactSourceTextDocument): SourceTextProjectionWireDocument? {
        return when (value) {
            CompactSourceTextDocument.NotRequested -> SourceTextProjectionWireDocument.NotRequested
            is CompactSourceTextDocument.Returned ->
                SourceTextProjectionWireDocument.Returned(
                    selection(value.selection) ?: return null,
                    value.text,
                    SourceLineRangeWireDocument(value.lines.startInclusive, value.lines.endInclusive),
                )
            is CompactSourceTextDocument.Withheld -> SourceTextProjectionWireDocument.Withheld(value.reason)
        }
    }

    private fun entity(value: CompactSourceEntityDocument): SourceEntityWireDocument? {
        return when (value) {
            is CompactSourceEntityDocument.Declaration ->
                SourceEntityWireDocument.Declaration(
                    value.kind,
                    value.name,
                    value.visibility,
                    value.nestingDepth,
                    token(value.parent) ?: return null,
                    selection(value.selection) ?: return null,
                    SourceDeclarationSemanticIdentityWireDocument.Candidate(value.target.selector),
                )
            is CompactSourceEntityDocument.ValueParameter -> parameter(value)
            is CompactSourceEntityDocument.Call ->
                SourceEntityWireDocument.Call(
                    value.nestingDepth,
                    token(value.parent) ?: return null,
                    selection(value.selection) ?: return null,
                    selection(value.callee) ?: return null,
                    target(value.target) ?: return null,
                )
            is CompactSourceEntityDocument.Reference ->
                SourceEntityWireDocument.Reference(
                    value.name,
                    value.nestingDepth,
                    token(value.parent) ?: return null,
                    selection(value.selection) ?: return null,
                    target(value.target) ?: return null,
                )
        }
    }

    private fun parameter(value: CompactSourceEntityDocument.ValueParameter): SourceEntityWireDocument? =
        SourceEntityWireDocument.ValueParameter(
            value.name,
            value.nestingDepth,
            token(value.parent) ?: return null,
            selection(value.selection) ?: return null,
        )

    fun project(): SourceReadResultWireDocument? =
        with(document) {
            val regionKind = region.kind
            val expandedText = text(text) ?: return null
            val rows = entities.map { entity(it) ?: return null }
            SourceReadResultWireDocument(
                SourceSnapshotWireDocument(
                    snapshot.canonicalRoot,
                    snapshot.generation,
                    snapshot.sourceState,
                    snapshot.file,
                    snapshot.textIdentity,
                    SourceCoordinateUnitWireDocument.UTF16_CODE_UNIT,
                    snapshot.length,
                    snapshot.live?.let { LiveEvidenceDocument(it.root, it.host, it.epoch, it.contentView, it.version) },
                ),
                SourceRegionWireDocument(regionKind, selection(region.selection) ?: return null),
                rows,
                expandedText,
                executionBudget,
            )
        }
}
