package io.github.amichne.kast.protocol.wire

/** Generated topology wire documents, structural refinement, and serializer bindings. */

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildResult
import io.github.amichne.kast.protocol.contract.TopologyCoverageCandidateEvidenceMismatch
import io.github.amichne.kast.protocol.contract.TopologyCoverageFileEvidence
import io.github.amichne.kast.protocol.contract.TopologyCoverageProjectionRejection
import io.github.amichne.kast.protocol.contract.TopologyCoverageSourceHash
import io.github.amichne.kast.protocol.contract.TopologyCoverageSourceRootEvidence
import io.github.amichne.kast.protocol.contract.TopologyCoverageSourceRootProvenance
import io.github.amichne.kast.protocol.contract.TopologyCoverageWorkspaceEvidence
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TopologyCoverageCandidateEvidenceMismatchDocument(
    val candidate: TopologyCoverageFileEvidenceDocument,
    val completed: TopologyCoverageFileEvidenceDocument,
)

@Serializable
internal data class TopologyCoverageFileEvidenceDocument(
    val workspace: TopologyCoverageWorkspaceEvidenceDocument,
    val sourceRoot: TopologyCoverageSourceRootEvidenceDocument,
    val path: String,
    val contentHash: String,
)

@Serializable
internal data class TopologyCoverageWorkspaceEvidenceDocument(
    val root: String,
    val generation: Long,
    val sourceState: String,
)

@Serializable
internal data class TopologyCoverageSourceRootEvidenceDocument(
    val module: String,
    val buildRoot: String,
    val projectPath: String,
    val sourceSet: String,
    val location: String,
    val provenance: TopologyCoverageSourceRootProvenanceDocument,
)

@Serializable
internal enum class TopologyCoverageSourceRootProvenanceDocument {
    @SerialName("authored") AUTHORED,
    @SerialName("generated") GENERATED,
    @SerialName("unknown-excluded-from-source-model") UNKNOWN_EXCLUDED_FROM_SOURCE_MODEL,
}

@Serializable
internal enum class TopologyCoverageProjectionFailureDocument {
    @SerialName("unrepresentable-text") UNREPRESENTABLE_TEXT,
    @SerialName("unrepresentable-range") UNREPRESENTABLE_RANGE,
    @SerialName("unrepresentable-content-hash") UNREPRESENTABLE_CONTENT_HASH,
    @SerialName("unrepresentable-compiler-evidence") UNREPRESENTABLE_COMPILER_EVIDENCE,
    @SerialName("empty-failure") EMPTY_FAILURE,
}

internal fun TopologyCoverageCandidateEvidenceMismatch.toSerializableDocument() =
    TopologyCoverageCandidateEvidenceMismatchDocument(
        candidate = candidate.toSerializableDocument(),
        completed = completed.toSerializableDocument(),
    )

internal fun TopologyCoverageFileEvidence.toSerializableDocument() =
    TopologyCoverageFileEvidenceDocument(
        workspace = TopologyCoverageWorkspaceEvidenceDocument(
            root = workspace.root.value,
            generation = workspace.generation.value,
            sourceState = workspace.sourceState.value,
        ),
        sourceRoot = TopologyCoverageSourceRootEvidenceDocument(
            module = sourceRoot.module.value,
            buildRoot = sourceRoot.buildRoot.value,
            projectPath = sourceRoot.projectPath.value,
            sourceSet = sourceRoot.sourceSet.value,
            location = sourceRoot.location.value,
            provenance = sourceRoot.provenance.toSerializableDocument(),
        ),
        path = path.value,
        contentHash = contentHash.value,
    )

/**
 * Proof transition: `TopologyCoverageCandidateEvidenceMismatchDocument ->
 * TopologyCoverageCandidateEvidenceMismatch`.
 *
 * Establishes exact candidate and completed file evidence. [WireDocumentConversion.Rejected] is
 * the closed expected failure. Raw document fields remain inside the generated wire boundary.
 */
internal fun TopologyCoverageCandidateEvidenceMismatchDocument.toContract():
    WireDocumentConversion<TopologyCoverageCandidateEvidenceMismatch> = combineConverted(
    candidate.toContract(),
    completed.toContract(),
    ::TopologyCoverageCandidateEvidenceMismatch,
)

/**
 * Proof transition: `TopologyCoverageFileEvidenceDocument -> TopologyCoverageFileEvidence`.
 *
 * Establishes exact workspace, source-root, path, and SHA-256 evidence.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw document fields remain
 * inside the generated wire boundary.
 */
internal fun TopologyCoverageFileEvidenceDocument.toContract():
    WireDocumentConversion<TopologyCoverageFileEvidence> = combineConverted(
    workspace.toContract(),
    sourceRoot.toContract(),
    path.evidenceProtocolText(),
    TopologyCoverageSourceHash.parse(contentHash).toWireDocumentConversion(),
    ::TopologyCoverageFileEvidence,
)

/**
 * Proof transition: `TopologyCoverageWorkspaceEvidenceDocument ->
 * TopologyCoverageWorkspaceEvidence`.
 *
 * Establishes refined root, generation, and source-state evidence.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw document fields remain at
 * this generated wire boundary.
 */
private fun TopologyCoverageWorkspaceEvidenceDocument.toContract():
    WireDocumentConversion<TopologyCoverageWorkspaceEvidence> = combineConverted(
    root.evidenceProtocolText(),
    EvidenceGeneration.parse(generation).toWireDocumentConversion(),
    sourceState.evidenceProtocolText(),
    ::TopologyCoverageWorkspaceEvidence,
)

/**
 * Proof transition: `TopologyCoverageSourceRootEvidenceDocument ->
 * TopologyCoverageSourceRootEvidence`.
 *
 * Establishes refined imported-root identities and closed provenance.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw document fields remain at
 * this generated wire boundary.
 */
private fun TopologyCoverageSourceRootEvidenceDocument.toContract():
    WireDocumentConversion<TopologyCoverageSourceRootEvidence> = combineConverted(
    module.evidenceProtocolText(),
    buildRoot.evidenceProtocolText(),
    projectPath.evidenceProtocolText(),
    sourceSet.evidenceProtocolText(),
    location.evidenceProtocolText(),
) { module, buildRoot, projectPath, sourceSet, location ->
    TopologyCoverageSourceRootEvidence(
        module,
        buildRoot,
        projectPath,
        sourceSet,
        location,
        provenance.toContract(),
    )
}

private fun TopologyCoverageSourceRootProvenance.toSerializableDocument() = when (this) {
    TopologyCoverageSourceRootProvenance.AUTHORED ->
        TopologyCoverageSourceRootProvenanceDocument.AUTHORED
    TopologyCoverageSourceRootProvenance.GENERATED ->
        TopologyCoverageSourceRootProvenanceDocument.GENERATED
    TopologyCoverageSourceRootProvenance.UNKNOWN_EXCLUDED_FROM_SOURCE_MODEL ->
        TopologyCoverageSourceRootProvenanceDocument.UNKNOWN_EXCLUDED_FROM_SOURCE_MODEL
}

private fun TopologyCoverageSourceRootProvenanceDocument.toContract() = when (this) {
    TopologyCoverageSourceRootProvenanceDocument.AUTHORED ->
        TopologyCoverageSourceRootProvenance.AUTHORED
    TopologyCoverageSourceRootProvenanceDocument.GENERATED ->
        TopologyCoverageSourceRootProvenance.GENERATED
    TopologyCoverageSourceRootProvenanceDocument.UNKNOWN_EXCLUDED_FROM_SOURCE_MODEL ->
        TopologyCoverageSourceRootProvenance.UNKNOWN_EXCLUDED_FROM_SOURCE_MODEL
}

internal fun TopologyCoverageProjectionRejection.toDocument() = when (this) {
    TopologyCoverageProjectionRejection.UNREPRESENTABLE_TEXT ->
        TopologyCoverageProjectionFailureDocument.UNREPRESENTABLE_TEXT
    TopologyCoverageProjectionRejection.UNREPRESENTABLE_RANGE ->
        TopologyCoverageProjectionFailureDocument.UNREPRESENTABLE_RANGE
    TopologyCoverageProjectionRejection.UNREPRESENTABLE_CONTENT_HASH ->
        TopologyCoverageProjectionFailureDocument.UNREPRESENTABLE_CONTENT_HASH
    TopologyCoverageProjectionRejection.UNREPRESENTABLE_COMPILER_EVIDENCE ->
        TopologyCoverageProjectionFailureDocument.UNREPRESENTABLE_COMPILER_EVIDENCE
    TopologyCoverageProjectionRejection.EMPTY_FAILURE ->
        TopologyCoverageProjectionFailureDocument.EMPTY_FAILURE
}

internal fun TopologyCoverageProjectionFailureDocument.toContract() = when (this) {
    TopologyCoverageProjectionFailureDocument.UNREPRESENTABLE_TEXT ->
        TopologyCoverageProjectionRejection.UNREPRESENTABLE_TEXT
    TopologyCoverageProjectionFailureDocument.UNREPRESENTABLE_RANGE ->
        TopologyCoverageProjectionRejection.UNREPRESENTABLE_RANGE
    TopologyCoverageProjectionFailureDocument.UNREPRESENTABLE_CONTENT_HASH ->
        TopologyCoverageProjectionRejection.UNREPRESENTABLE_CONTENT_HASH
    TopologyCoverageProjectionFailureDocument.UNREPRESENTABLE_COMPILER_EVIDENCE ->
        TopologyCoverageProjectionRejection.UNREPRESENTABLE_COMPILER_EVIDENCE
    TopologyCoverageProjectionFailureDocument.EMPTY_FAILURE ->
        TopologyCoverageProjectionRejection.EMPTY_FAILURE
}

/** `String -> ProtocolText`; closes invalid text at this generated file-evidence boundary. */
private fun String.evidenceProtocolText(): WireDocumentConversion<ProtocolText> =
    ProtocolText.parse(this).toWireDocumentConversion()

internal val topologyCoverageFileEvidenceDocumentComparator =
    compareBy<TopologyCoverageFileEvidenceDocument>(
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

internal val topologyCoverageCandidateEvidenceMismatchDocumentComparator =
    Comparator<TopologyCoverageCandidateEvidenceMismatchDocument> { left, right ->
        val candidateOrder = topologyCoverageFileEvidenceDocumentComparator.compare(
            left.candidate,
            right.candidate,
        )
        if (candidateOrder != 0) {
            candidateOrder
        } else {
            topologyCoverageFileEvidenceDocumentComparator.compare(left.completed, right.completed)
        }
    }

private fun TopologyCoverageSourceRootProvenanceDocument.sortRank(): Int = when (this) {
    TopologyCoverageSourceRootProvenanceDocument.AUTHORED -> 0
    TopologyCoverageSourceRootProvenanceDocument.GENERATED -> 1
    TopologyCoverageSourceRootProvenanceDocument.UNKNOWN_EXCLUDED_FROM_SOURCE_MODEL -> 2
}

internal object CanonicalTopologySerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)

    val request = factory.create(
        TopologyBuildRequestDocument.serializer(),
        { TopologyBuildRequestDocument },
        { WireDocumentConversion.Converted(TopologyBuildRequest) },
    )
    val result = factory.create(
        TopologyBuildResultDocument.serializer(),
        TopologyBuildResult::toSerializableDocument,
        TopologyBuildResultDocument::toContract,
    )
    val qualification = factory.create(
        TopologyBuildQualificationDocument.serializer(),
        TopologyBuildQualification::toSerializableDocument,
        TopologyBuildQualificationDocument::toContract,
    )
    val rejection = factory.create(
        TopologyBuildRejectionDocument.serializer(),
        TopologyBuildRejection::toSerializableDocument,
        TopologyBuildRejectionDocument::toContract,
    )
}
