@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.TopologyCoverageCandidateEvidenceMismatch
import io.github.amichne.kast.protocol.contract.TopologyCoverageFailure
import io.github.amichne.kast.protocol.contract.TopologyCoverageNode
import io.github.amichne.kast.protocol.contract.TopologyCoverageQualifiedIdentity
import io.github.amichne.kast.protocol.contract.TopologyCoverageSymbol
import io.github.amichne.kast.protocol.contract.TopologyCoverageSymbolKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
internal data class TopologyCoverageFailureDocument(
    val missing: List<String>,
    val unexpected: List<String>,
    val duplicateCandidates: List<String>,
    val duplicateCompletions: List<String>,
    val workspaceMismatches: List<String>,
    val candidateEvidenceMismatches: List<TopologyCoverageCandidateEvidenceMismatchDocument>,
    val duplicateSymbols: List<TopologyCoverageNodeDocument>,
    val missingEdgeTargets: List<TopologyCoverageNodeDocument>,
    val mismatchedEdgeEndpoints: List<TopologyCoverageSymbolDocument>,
)

@Serializable
internal data class TopologyCoverageNodeDocument(
    val compilerIdentity: String,
    val file: String,
    val range: TopologyCoverageRangeDocument,
)

@Serializable
internal data class TopologyCoverageRangeDocument(
    val startInclusive: Int,
    val endExclusive: Int,
)

@Serializable
internal data class TopologyCoverageSymbolDocument(
    val node: TopologyCoverageNodeDocument,
    val fileEvidence: TopologyCoverageFileEvidenceDocument,
    val name: String,
    val qualifiedIdentity: TopologyCoverageQualifiedIdentityDocument,
    val kind: TopologyCoverageSymbolKindDocument,
)

@Serializable
@JsonClassDiscriminator("state")
internal sealed interface TopologyCoverageQualifiedIdentityDocument {
    @Serializable
    @SerialName("available")
    data class Available(val value: String) : TopologyCoverageQualifiedIdentityDocument

    @Serializable
    @SerialName("unavailable")
    data object Unavailable : TopologyCoverageQualifiedIdentityDocument
}

@Serializable
internal enum class TopologyCoverageSymbolKindDocument {
    @SerialName("classlike") CLASSLIKE,
    @SerialName("constructor") CONSTRUCTOR,
    @SerialName("function") FUNCTION,
    @SerialName("property") PROPERTY,
    @SerialName("type-alias") TYPE_ALIAS,
}

private data class TopologyCoverageTextSets(
    val missing: Set<ProtocolText>,
    val unexpected: Set<ProtocolText>,
    val duplicateCandidates: Set<ProtocolText>,
    val duplicateCompletions: Set<ProtocolText>,
    val workspaceMismatches: Set<ProtocolText>,
)

private data class TopologyCoverageEvidenceSets(
    val candidateEvidenceMismatches: Set<TopologyCoverageCandidateEvidenceMismatch>,
    val duplicateSymbols: Set<TopologyCoverageNode>,
    val missingEdgeTargets: Set<TopologyCoverageNode>,
    val mismatchedEdgeEndpoints: Set<TopologyCoverageSymbol>,
)

internal fun TopologyCoverageFailure.toSerializableDocument(): TopologyCoverageFailureDocument =
    TopologyCoverageFailureDocument(
        missing = missing.map(ProtocolText::value).sorted(),
        unexpected = unexpected.map(ProtocolText::value).sorted(),
        duplicateCandidates = duplicateCandidates.map(ProtocolText::value).sorted(),
        duplicateCompletions = duplicateCompletions.map(ProtocolText::value).sorted(),
        workspaceMismatches = workspaceMismatches.map(ProtocolText::value).sorted(),
        candidateEvidenceMismatches = candidateEvidenceMismatches
            .map(TopologyCoverageCandidateEvidenceMismatch::toSerializableDocument)
            .sortedWith(topologyCoverageCandidateEvidenceMismatchDocumentComparator),
        duplicateSymbols = duplicateSymbols.map(TopologyCoverageNode::toSerializableDocument)
            .sortedWith(topologyCoverageNodeDocumentComparator),
        missingEdgeTargets = missingEdgeTargets.map(TopologyCoverageNode::toSerializableDocument)
            .sortedWith(topologyCoverageNodeDocumentComparator),
        mismatchedEdgeEndpoints = mismatchedEdgeEndpoints
            .map(TopologyCoverageSymbol::toSerializableDocument)
            .sortedWith(topologyCoverageSymbolDocumentComparator),
    )

/**
 * Proof transition: `TopologyCoverageFailureDocument -> TopologyCoverageFailure`.
 *
 * Establishes canonical distinct collections, refined text and ranges, and a non-empty exact
 * failure. [WireDocumentConversion.Rejected] is the closed expected failure. Raw primitives
 * remain inside this generated-document adapter.
 */
internal fun TopologyCoverageFailureDocument.toContract():
    WireDocumentConversion<TopologyCoverageFailure> {
    val textSets = combineConverted(
        missing.canonicalTexts(),
        unexpected.canonicalTexts(),
        duplicateCandidates.canonicalTexts(),
        duplicateCompletions.canonicalTexts(),
        workspaceMismatches.canonicalTexts(),
        ::TopologyCoverageTextSets,
    )
    val evidenceSets = combineConverted(
        candidateEvidenceMismatches.canonicalValues(
            topologyCoverageCandidateEvidenceMismatchDocumentComparator,
            TopologyCoverageCandidateEvidenceMismatchDocument::toContract,
        ),
        duplicateSymbols.canonicalValues(
            topologyCoverageNodeDocumentComparator,
            TopologyCoverageNodeDocument::toContract,
        ),
        missingEdgeTargets.canonicalValues(
            topologyCoverageNodeDocumentComparator,
            TopologyCoverageNodeDocument::toContract,
        ),
        mismatchedEdgeEndpoints.canonicalValues(
            topologyCoverageSymbolDocumentComparator,
            TopologyCoverageSymbolDocument::toContract,
        ),
        ::TopologyCoverageEvidenceSets,
    )
    return combineConverted(textSets, evidenceSets, ::Pair).flatMapConverted { (texts, evidence) ->
        TopologyCoverageFailure.admit(
            texts.missing,
            texts.unexpected,
            texts.duplicateCandidates,
            texts.duplicateCompletions,
            texts.workspaceMismatches,
            evidence.candidateEvidenceMismatches,
            evidence.duplicateSymbols,
            evidence.missingEdgeTargets,
            evidence.mismatchedEdgeEndpoints,
        ).toWireDocumentConversion()
    }
}

private fun TopologyCoverageNode.toSerializableDocument() = TopologyCoverageNodeDocument(
    compilerIdentity.value,
    file.value,
    TopologyCoverageRangeDocument(range.startInclusive.value, range.endExclusive.value),
)

private fun TopologyCoverageSymbol.toSerializableDocument() = TopologyCoverageSymbolDocument(
    node.toSerializableDocument(),
    fileEvidence.toSerializableDocument(),
    name.value,
    when (val identity = qualifiedIdentity) {
        is TopologyCoverageQualifiedIdentity.Available ->
            TopologyCoverageQualifiedIdentityDocument.Available(identity.value.value)
        TopologyCoverageQualifiedIdentity.Unavailable ->
            TopologyCoverageQualifiedIdentityDocument.Unavailable
    },
    when (kind) {
        TopologyCoverageSymbolKind.CLASSLIKE -> TopologyCoverageSymbolKindDocument.CLASSLIKE
        TopologyCoverageSymbolKind.CONSTRUCTOR -> TopologyCoverageSymbolKindDocument.CONSTRUCTOR
        TopologyCoverageSymbolKind.FUNCTION -> TopologyCoverageSymbolKindDocument.FUNCTION
        TopologyCoverageSymbolKind.PROPERTY -> TopologyCoverageSymbolKindDocument.PROPERTY
        TopologyCoverageSymbolKind.TYPE_ALIAS -> TopologyCoverageSymbolKindDocument.TYPE_ALIAS
    },
)

/**
 * Proof transition: `TopologyCoverageNodeDocument -> TopologyCoverageNode`.
 *
 * Establishes refined compiler/file identities and an ordered source range.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw fields are extracted only
 * from the generated coverage document.
 */
private fun TopologyCoverageNodeDocument.toContract(): WireDocumentConversion<TopologyCoverageNode> =
    combineConverted(
        compilerIdentity.protocolText(),
        file.protocolText(),
        range.toContract(),
        ::TopologyCoverageNode,
    )

/**
 * Proof transition: `TopologyCoverageRangeDocument -> SourceRangeDocument`.
 *
 * Establishes non-negative ordered offsets. [WireDocumentConversion.Rejected] is the closed
 * expected failure. Raw offsets remain inside this generated-document adapter.
 */
private fun TopologyCoverageRangeDocument.toContract(): WireDocumentConversion<SourceRangeDocument> =
    combineConverted(
        startInclusive.protocolOffset(),
        endExclusive.protocolOffset(),
        ::Pair,
    ).flatMapConverted { (start, end) ->
        SourceRangeDocument.create(start, end).toWireDocumentConversion()
    }

/**
 * Proof transition: `TopologyCoverageSymbolDocument -> TopologyCoverageSymbol`.
 *
 * Establishes exact node, file, name, qualified-identity, and kind evidence.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw fields are extracted only
 * from the generated coverage document.
 */
private fun TopologyCoverageSymbolDocument.toContract():
    WireDocumentConversion<TopologyCoverageSymbol> = combineConverted(
    node.toContract(),
    fileEvidence.toContract(),
    name.protocolText(),
    qualifiedIdentity.toContract(),
) { node, fileEvidence, name, qualifiedIdentity ->
    TopologyCoverageSymbol(node, fileEvidence, name, qualifiedIdentity, kind.toContract())
}

/**
 * Proof transition: `TopologyCoverageQualifiedIdentityDocument ->
 * TopologyCoverageQualifiedIdentity`.
 *
 * Establishes explicit availability and refined available text. [WireDocumentConversion.Rejected]
 * is the closed expected failure. Raw identity text remains in this generated-document adapter.
 */
private fun TopologyCoverageQualifiedIdentityDocument.toContract():
    WireDocumentConversion<TopologyCoverageQualifiedIdentity> = when (this) {
    is TopologyCoverageQualifiedIdentityDocument.Available -> value.protocolText().mapConverted {
        TopologyCoverageQualifiedIdentity.Available(it)
    }
    TopologyCoverageQualifiedIdentityDocument.Unavailable -> WireDocumentConversion.Converted(
        TopologyCoverageQualifiedIdentity.Unavailable,
    )
}

private fun TopologyCoverageSymbolKindDocument.toContract(): TopologyCoverageSymbolKind = when (this) {
    TopologyCoverageSymbolKindDocument.CLASSLIKE -> TopologyCoverageSymbolKind.CLASSLIKE
    TopologyCoverageSymbolKindDocument.CONSTRUCTOR -> TopologyCoverageSymbolKind.CONSTRUCTOR
    TopologyCoverageSymbolKindDocument.FUNCTION -> TopologyCoverageSymbolKind.FUNCTION
    TopologyCoverageSymbolKindDocument.PROPERTY -> TopologyCoverageSymbolKind.PROPERTY
    TopologyCoverageSymbolKindDocument.TYPE_ALIAS -> TopologyCoverageSymbolKind.TYPE_ALIAS
}

/**
 * Proof transition: `List<String> -> Set<ProtocolText>`.
 *
 * Establishes canonical ordering, uniqueness, and refined values.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw strings are admitted only
 * from the generated coverage document.
 */
private fun List<String>.canonicalTexts(): WireDocumentConversion<Set<ProtocolText>> =
    canonicalValues(naturalOrder(), String::protocolText)

/**
 * Proof transition: `List<Document> -> Set<Value>`.
 *
 * Establishes canonical ordering and uniqueness before retaining converted values.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw collections remain at the
 * generated-document boundary.
 */
private fun <Document, Value> List<Document>.canonicalValues(
    comparator: Comparator<Document>,
    conversion: (Document) -> WireDocumentConversion<Value>,
): WireDocumentConversion<Set<Value>> {
    if (this != distinct().sortedWith(comparator)) {
        return WireDocumentConversion.Rejected
    }
    return convertEach(conversion).mapConverted { it.toCollection(linkedSetOf()) }
}

/**
 * Proof transition: `String -> ProtocolText`.
 *
 * Establishes non-blank bounded text. [WireDocumentConversion.Rejected] is the closed expected
 * failure. Raw strings are admitted only from the generated coverage document.
 */
private fun String.protocolText(): WireDocumentConversion<ProtocolText> =
    ProtocolText.parse(this).toWireDocumentConversion()

/**
 * Proof transition: `Int -> ProtocolOffset`.
 *
 * Establishes a non-negative offset. [WireDocumentConversion.Rejected] is the closed expected
 * failure. Raw integers are admitted only from the generated coverage document.
 */
private fun Int.protocolOffset(): WireDocumentConversion<ProtocolOffset> =
    ProtocolOffset.parse(this).toWireDocumentConversion()

private val topologyCoverageNodeDocumentComparator =
    compareBy<TopologyCoverageNodeDocument>(
        { it.compilerIdentity },
        { it.file },
        { it.range.startInclusive },
        { it.range.endExclusive },
    )

private val topologyCoverageSymbolDocumentComparator =
    Comparator<TopologyCoverageSymbolDocument> { left, right ->
        val nodeOrder = topologyCoverageNodeDocumentComparator.compare(left.node, right.node)
        val evidenceOrder = if (nodeOrder == 0) {
            topologyCoverageFileEvidenceDocumentComparator.compare(
                left.fileEvidence,
                right.fileEvidence,
            )
        } else {
            nodeOrder
        }
        if (evidenceOrder != 0) {
            evidenceOrder
        } else {
            compareValuesBy(
                left,
                right,
                { it.name },
                { it.qualifiedIdentity.sortRank() },
                { it.qualifiedIdentity.sortValue() },
                { it.kind.sortRank() },
            )
        }
    }

private fun TopologyCoverageQualifiedIdentityDocument.sortRank(): Int = when (this) {
    is TopologyCoverageQualifiedIdentityDocument.Available -> 0
    TopologyCoverageQualifiedIdentityDocument.Unavailable -> 1
}

private fun TopologyCoverageQualifiedIdentityDocument.sortValue(): String = when (this) {
    is TopologyCoverageQualifiedIdentityDocument.Available -> value
    TopologyCoverageQualifiedIdentityDocument.Unavailable -> ""
}

private fun TopologyCoverageSymbolKindDocument.sortRank(): Int = when (this) {
    TopologyCoverageSymbolKindDocument.CLASSLIKE -> 0
    TopologyCoverageSymbolKindDocument.CONSTRUCTOR -> 1
    TopologyCoverageSymbolKindDocument.FUNCTION -> 2
    TopologyCoverageSymbolKindDocument.PROPERTY -> 3
    TopologyCoverageSymbolKindDocument.TYPE_ALIAS -> 4
}
