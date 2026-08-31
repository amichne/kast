@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyCoverageCandidateEvidenceMismatch
import io.github.amichne.kast.protocol.contract.TopologyCoverageFileEvidence
import io.github.amichne.kast.protocol.contract.TopologyCoverageNode
import io.github.amichne.kast.protocol.contract.TopologyCoverageQualifiedIdentity
import io.github.amichne.kast.protocol.contract.TopologyCoverageSourceRootProvenance
import io.github.amichne.kast.protocol.contract.TopologyCoverageSymbol
import io.github.amichne.kast.protocol.contract.TopologyCoverageSymbolKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
private data class TopologyCoverageRejectedCliDocument(
    val operation: String,
    val status: String,
    val reason: String,
    val missing: List<String>,
    val unexpected: List<String>,
    val duplicateCandidates: List<String>,
    val duplicateCompletions: List<String>,
    val workspaceMismatches: List<String>,
    val candidateEvidenceMismatches: List<TopologyCoverageCandidateEvidenceMismatchCliDocument>,
    val duplicateSymbols: List<TopologyCoverageNodeCliDocument>,
    val missingEdgeTargets: List<TopologyCoverageNodeCliDocument>,
    val mismatchedEdgeEndpoints: List<TopologyCoverageSymbolCliDocument>,
)

@Serializable
private data class TopologyCoverageNodeCliDocument(
    val compilerIdentity: String,
    val file: String,
    val range: TopologyCoverageRangeCliDocument,
)

@Serializable
private data class TopologyCoverageRangeCliDocument(
    val startInclusive: Int,
    val endExclusive: Int,
)

@Serializable
private data class TopologyCoverageSymbolCliDocument(
    val node: TopologyCoverageNodeCliDocument,
    val fileEvidence: TopologyCoverageFileEvidenceCliDocument,
    val name: String,
    val qualifiedIdentity: TopologyCoverageQualifiedIdentityCliDocument,
    val kind: TopologyCoverageSymbolKindCliDocument,
    val compilerEvidence: CompilerSymbolEvidenceCliDocument,
)

@Serializable
private data class TopologyCoverageCandidateEvidenceMismatchCliDocument(
    val candidate: TopologyCoverageFileEvidenceCliDocument,
    val completed: TopologyCoverageFileEvidenceCliDocument,
)

@Serializable
private data class TopologyCoverageFileEvidenceCliDocument(
    val workspace: TopologyCoverageWorkspaceEvidenceCliDocument,
    val sourceRoot: TopologyCoverageSourceRootEvidenceCliDocument,
    val path: String,
    val contentHash: String,
)

@Serializable
private data class TopologyCoverageWorkspaceEvidenceCliDocument(
    val root: String,
    val generation: Long,
    val sourceState: String,
)

@Serializable
private data class TopologyCoverageSourceRootEvidenceCliDocument(
    val module: String,
    val buildRoot: String,
    val projectPath: String,
    val sourceSet: String,
    val location: String,
    val provenance: TopologyCoverageSourceRootProvenanceCliDocument,
)

@Serializable
private enum class TopologyCoverageSourceRootProvenanceCliDocument {
    @SerialName("authored") AUTHORED,
    @SerialName("generated") GENERATED,
    @SerialName("unknown-excluded-from-source-model") UNKNOWN_EXCLUDED_FROM_SOURCE_MODEL,
}

@Serializable
@JsonClassDiscriminator("state")
private sealed interface TopologyCoverageQualifiedIdentityCliDocument {
    @Serializable
    @SerialName("available")
    data class Available(val value: String) : TopologyCoverageQualifiedIdentityCliDocument

    @Serializable
    @SerialName("unavailable")
    data object Unavailable : TopologyCoverageQualifiedIdentityCliDocument
}

@Serializable
private enum class TopologyCoverageSymbolKindCliDocument {
    @SerialName("classlike") CLASSLIKE,
    @SerialName("constructor") CONSTRUCTOR,
    @SerialName("function") FUNCTION,
    @SerialName("property") PROPERTY,
    @SerialName("type-alias") TYPE_ALIAS,
}

/** Preserves every exact coverage mismatch in one generated closed CLI rejection document. */
internal fun TopologyBuildRejection.CoverageIncomplete.coverageRejectedDocument():
    CliJsonDocument = topologyCoverageRejectedFactory.create(
    TopologyCoverageRejectedCliDocument(
        operation = CanonicalOperation.TOPOLOGY_BUILD.id.value,
        status = "rejected",
        reason = "coverage-incomplete",
        missing = failure.missing.sortedValues(),
        unexpected = failure.unexpected.sortedValues(),
        duplicateCandidates = failure.duplicateCandidates.sortedValues(),
        duplicateCompletions = failure.duplicateCompletions.sortedValues(),
        workspaceMismatches = failure.workspaceMismatches.sortedValues(),
        candidateEvidenceMismatches = failure.candidateEvidenceMismatches
            .map(TopologyCoverageCandidateEvidenceMismatch::toCliDocument)
            .sortedWith(topologyCoverageCandidateEvidenceMismatchCliDocumentComparator),
        duplicateSymbols = failure.duplicateSymbols.map(TopologyCoverageNode::toCliDocument)
            .sortedWith(topologyCoverageNodeCliDocumentComparator),
        missingEdgeTargets = failure.missingEdgeTargets.map(TopologyCoverageNode::toCliDocument)
            .sortedWith(topologyCoverageNodeCliDocumentComparator),
        mismatchedEdgeEndpoints = failure.mismatchedEdgeEndpoints
            .map(TopologyCoverageSymbol::toCliDocument)
            .sortedWith(topologyCoverageSymbolCliDocumentComparator),
    ),
)

private fun Set<ProtocolText>.sortedValues(): List<String> = map(ProtocolText::value).sorted()

private fun TopologyCoverageNode.toCliDocument(): TopologyCoverageNodeCliDocument =
    TopologyCoverageNodeCliDocument(
        compilerIdentity = compilerIdentity.value,
        file = file.value,
        range = TopologyCoverageRangeCliDocument(
            startInclusive = range.startInclusive.value,
            endExclusive = range.endExclusive.value,
        ),
    )

private fun TopologyCoverageSymbol.toCliDocument(): TopologyCoverageSymbolCliDocument =
    TopologyCoverageSymbolCliDocument(
        node = node.toCliDocument(),
        fileEvidence = fileEvidence.toCliDocument(),
        name = name.value,
        qualifiedIdentity = when (val identity = qualifiedIdentity) {
            is TopologyCoverageQualifiedIdentity.Available ->
                TopologyCoverageQualifiedIdentityCliDocument.Available(identity.value.value)
            TopologyCoverageQualifiedIdentity.Unavailable ->
                TopologyCoverageQualifiedIdentityCliDocument.Unavailable
        },
        kind = kind.toCliDocument(),
        compilerEvidence = compilerEvidence.toCliDocument(),
    )

private fun TopologyCoverageCandidateEvidenceMismatch.toCliDocument() =
    TopologyCoverageCandidateEvidenceMismatchCliDocument(
        candidate = candidate.toCliDocument(),
        completed = completed.toCliDocument(),
    )

private fun TopologyCoverageFileEvidence.toCliDocument() = TopologyCoverageFileEvidenceCliDocument(
    workspace = TopologyCoverageWorkspaceEvidenceCliDocument(
        root = workspace.root.value,
        generation = workspace.generation.value,
        sourceState = workspace.sourceState.value,
    ),
    sourceRoot = TopologyCoverageSourceRootEvidenceCliDocument(
        module = sourceRoot.module.value,
        buildRoot = sourceRoot.buildRoot.value,
        projectPath = sourceRoot.projectPath.value,
        sourceSet = sourceRoot.sourceSet.value,
        location = sourceRoot.location.value,
        provenance = sourceRoot.provenance.toCliDocument(),
    ),
    path = path.value,
    contentHash = contentHash.value,
)

private fun TopologyCoverageSourceRootProvenance.toCliDocument() = when (this) {
    TopologyCoverageSourceRootProvenance.AUTHORED ->
        TopologyCoverageSourceRootProvenanceCliDocument.AUTHORED
    TopologyCoverageSourceRootProvenance.GENERATED ->
        TopologyCoverageSourceRootProvenanceCliDocument.GENERATED
    TopologyCoverageSourceRootProvenance.UNKNOWN_EXCLUDED_FROM_SOURCE_MODEL ->
        TopologyCoverageSourceRootProvenanceCliDocument.UNKNOWN_EXCLUDED_FROM_SOURCE_MODEL
}

private fun TopologyCoverageSymbolKind.toCliDocument(): TopologyCoverageSymbolKindCliDocument =
    when (this) {
        TopologyCoverageSymbolKind.CLASSLIKE -> TopologyCoverageSymbolKindCliDocument.CLASSLIKE
        TopologyCoverageSymbolKind.CONSTRUCTOR -> TopologyCoverageSymbolKindCliDocument.CONSTRUCTOR
        TopologyCoverageSymbolKind.FUNCTION -> TopologyCoverageSymbolKindCliDocument.FUNCTION
        TopologyCoverageSymbolKind.PROPERTY -> TopologyCoverageSymbolKindCliDocument.PROPERTY
        TopologyCoverageSymbolKind.TYPE_ALIAS -> TopologyCoverageSymbolKindCliDocument.TYPE_ALIAS
    }

private val topologyCoverageNodeCliDocumentComparator = compareBy<TopologyCoverageNodeCliDocument>(
    { it.compilerIdentity },
    { it.file },
    { it.range.startInclusive },
    { it.range.endExclusive },
)

private val topologyCoverageSymbolCliDocumentComparator =
    Comparator<TopologyCoverageSymbolCliDocument> { left, right ->
        val nodeOrder = topologyCoverageNodeCliDocumentComparator.compare(left.node, right.node)
        val evidenceOrder = if (nodeOrder == 0) {
            topologyCoverageFileEvidenceCliDocumentComparator.compare(
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
                { it.compilerEvidence.identity },
            )
        }
    }

private val topologyCoverageFileEvidenceCliDocumentComparator =
    compareBy<TopologyCoverageFileEvidenceCliDocument>(
        { it.workspace.root },
        { it.workspace.generation },
        { it.workspace.sourceState },
        { it.sourceRoot.module },
        { it.sourceRoot.buildRoot },
        { it.sourceRoot.projectPath },
        { it.sourceRoot.sourceSet },
        { it.sourceRoot.location },
        { it.sourceRoot.provenance.sortRank() },
        { it.path },
        { it.contentHash },
    )

private val topologyCoverageCandidateEvidenceMismatchCliDocumentComparator =
    Comparator<TopologyCoverageCandidateEvidenceMismatchCliDocument> { left, right ->
        val candidateOrder = topologyCoverageFileEvidenceCliDocumentComparator.compare(
            left.candidate,
            right.candidate,
        )
        if (candidateOrder != 0) {
            candidateOrder
        } else {
            topologyCoverageFileEvidenceCliDocumentComparator.compare(left.completed, right.completed)
        }
    }

private fun TopologyCoverageQualifiedIdentityCliDocument.sortRank(): Int = when (this) {
    is TopologyCoverageQualifiedIdentityCliDocument.Available -> 0
    TopologyCoverageQualifiedIdentityCliDocument.Unavailable -> 1
}

private fun TopologyCoverageQualifiedIdentityCliDocument.sortValue(): String = when (this) {
    is TopologyCoverageQualifiedIdentityCliDocument.Available -> value
    TopologyCoverageQualifiedIdentityCliDocument.Unavailable -> ""
}

private fun TopologyCoverageSymbolKindCliDocument.sortRank(): Int = when (this) {
    TopologyCoverageSymbolKindCliDocument.CLASSLIKE -> 0
    TopologyCoverageSymbolKindCliDocument.CONSTRUCTOR -> 1
    TopologyCoverageSymbolKindCliDocument.FUNCTION -> 2
    TopologyCoverageSymbolKindCliDocument.PROPERTY -> 3
    TopologyCoverageSymbolKindCliDocument.TYPE_ALIAS -> 4
}

private fun TopologyCoverageSourceRootProvenanceCliDocument.sortRank(): Int = when (this) {
    TopologyCoverageSourceRootProvenanceCliDocument.AUTHORED -> 0
    TopologyCoverageSourceRootProvenanceCliDocument.GENERATED -> 1
    TopologyCoverageSourceRootProvenanceCliDocument.UNKNOWN_EXCLUDED_FROM_SOURCE_MODEL -> 2
}

private val topologyCoverageRejectedFactory =
    CliJsonDocument.generated(TopologyCoverageRejectedCliDocument.serializer())
