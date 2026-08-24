package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class CompleteTopologyFileFailure {
    SYMBOL_FILE_MISMATCH,
    EDGE_SOURCE_FILE_MISMATCH,
    DUPLICATE_SYMBOL_IDENTITY,
    NON_DETERMINISTIC_SYMBOLS,
    NON_DETERMINISTIC_EDGES,
}

/** Terminal compiler coverage for one admitted Kotlin file. */
class CompleteTopologyFile private constructor(
    val file: TopologySourceFile,
    val symbols: List<TopologySymbol>,
    val edges: List<TopologyEdge>,
) {
    companion object {
        /**
         * Proof transition: `(TopologySourceFile, List<TopologySymbol>, List<TopologyEdge>) ->
         * Refinement<CompleteTopologyFile, Set<CompleteTopologyFileFailure>>`.
         *
         * Establishes deterministic unique compiler facts whose declarations and outgoing edges
         * belong to the exact candidate file. [CompleteTopologyFileFailure] is the closed expected
         * failure. Only the terminal K2 extraction boundary may submit raw fact collections.
         */
        fun admit(
            file: TopologySourceFile,
            symbols: List<TopologySymbol>,
            edges: List<TopologyEdge>,
        ): Refinement<CompleteTopologyFile, Set<CompleteTopologyFileFailure>> {
            val failures = linkedSetOf<CompleteTopologyFileFailure>()
            if (symbols.any { it.file != file }) {
                failures += CompleteTopologyFileFailure.SYMBOL_FILE_MISMATCH
            }
            if (edges.any { it.source.file != file }) {
                failures += CompleteTopologyFileFailure.EDGE_SOURCE_FILE_MISMATCH
            }
            if (symbols.map(TopologySymbol::nodeIdentity).distinct().size != symbols.size) {
                failures += CompleteTopologyFileFailure.DUPLICATE_SYMBOL_IDENTITY
            }
            if (symbols != symbols.distinct().sorted()) {
                failures += CompleteTopologyFileFailure.NON_DETERMINISTIC_SYMBOLS
            }
            if (edges != edges.distinct().sorted()) {
                failures += CompleteTopologyFileFailure.NON_DETERMINISTIC_EDGES
            }
            return if (failures.isEmpty()) {
                Refinement.Refined(CompleteTopologyFile(file, symbols.toList(), edges.toList()))
            } else {
                Refinement.Rejected(failures)
            }
        }
    }
}

data class TopologyGenerationCoverageFailure(
    val missing: Set<WorkspaceSourcePath>,
    val unexpected: Set<WorkspaceSourcePath>,
    val duplicateCandidates: Set<WorkspaceSourcePath>,
    val duplicateCompletions: Set<WorkspaceSourcePath>,
    val workspaceMismatches: Set<WorkspaceSourcePath>,
    val candidateEvidenceMismatches: Set<TopologyCandidateEvidenceMismatch>,
    val duplicateSymbols: Set<TopologyNodeIdentity>,
    val missingEdgeTargets: Set<TopologyNodeIdentity>,
    val mismatchedEdgeEndpoints: Set<TopologySymbol>,
)

/** Two same-path topology files whose retained ownership or content evidence differs. */
@ConsistentCopyVisibility
data class TopologyCandidateEvidenceMismatch private constructor(
    val candidate: TopologySourceFile,
    val completed: TopologySourceFile,
) {
    companion object {
        /**
         * Proof transition: `(TopologySourceFile, TopologySourceFile) ->
         * TopologyCandidateEvidenceComparison`.
         *
         * A mismatched result establishes one same-path, non-equal candidate/completion pair while
         * retaining both exact workspace, source-root, path, and content-hash values. Exact and
         * different-path inputs remain distinct closed states. Completed compiler evidence may
         * enter only at complete-generation admission.
         */
        internal fun compare(
            candidate: TopologySourceFile,
            completed: TopologySourceFile,
        ): TopologyCandidateEvidenceComparison = when {
            candidate.path != completed.path -> TopologyCandidateEvidenceComparison.DifferentPath
            candidate == completed -> TopologyCandidateEvidenceComparison.Exact
            else -> TopologyCandidateEvidenceComparison.Mismatched(
                TopologyCandidateEvidenceMismatch(
                    candidate,
                    completed,
                ),
            )
        }
    }
}

internal sealed interface TopologyCandidateEvidenceComparison {
    data object Exact : TopologyCandidateEvidenceComparison
    data object DifferentPath : TopologyCandidateEvidenceComparison
    data class Mismatched(
        val evidence: TopologyCandidateEvidenceMismatch,
    ) : TopologyCandidateEvidenceComparison
}

/** Lowercase SHA-256 over the canonical complete topology projection. */
@JvmInline
value class TopologyGenerationDigest internal constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<TopologyGenerationDigest,
         * TopologyGenerationDigestFailure>`.
         *
         * Establishes the canonical lowercase SHA-256 form used by a persisted topology
         * manifest. [TopologyGenerationDigestFailure] is the closed expected failure. Raw digest
         * text may enter only at the SQLite result-set boundary.
         */
        fun parse(
            raw: String,
        ): Refinement<TopologyGenerationDigest, TopologyGenerationDigestFailure> =
            if (raw.length == 64 && raw.all { it in '0'..'9' || it in 'a'..'f' }) {
                Refinement.Refined(TopologyGenerationDigest(raw))
            } else {
                Refinement.Rejected(TopologyGenerationDigestFailure.INVALID_FORMAT)
            }
    }
}

enum class TopologyGenerationDigestFailure {
    INVALID_FORMAT,
}

/** Exact complete generation that alone may cross the topology publication boundary. */
class CompleteTopologyGeneration private constructor(
    val identity: TopologyWorkspaceIdentity,
    val files: List<CompleteTopologyFile>,
    val digest: TopologyGenerationDigest,
) {
    val symbols: List<TopologySymbol> = files.flatMap(CompleteTopologyFile::symbols).sorted()
    val edges: List<TopologyEdge> = files.flatMap(CompleteTopologyFile::edges).sorted()

    fun canonicalProjection(): String = topologyCanonicalProjection(identity, files)

    companion object {
        /**
         * Proof transition: `(PublishedWorkspace, List<TopologySourceFile>,
         * List<CompleteTopologyFile>) -> Refinement<CompleteTopologyGeneration,
         * TopologyGenerationCoverageFailure>`.
         *
         * Establishes exact candidate-to-terminal coverage, one workspace identity,
         * location-distinct topology symbols, closed exact edge targets, canonical ordering, and
         * a deterministic generation digest. Compiler identity may repeat at distinct exact
         * locations. [TopologyGenerationCoverageFailure] retains every finite coverage mismatch.
         * Only the explicit topology build coordinator may call this transition.
         */
        fun admit(
            workspace: PublishedWorkspace,
            candidates: List<TopologySourceFile>,
            completed: List<CompleteTopologyFile>,
        ): Refinement<CompleteTopologyGeneration, TopologyGenerationCoverageFailure> {
            val identity = TopologyWorkspaceIdentity.from(workspace)
            val candidateGroups = candidates.groupBy { it.path }
            val completedGroups = completed.groupBy { it.file.path }
            val duplicateCandidates = duplicatePaths(candidateGroups)
            val duplicateCompletions = duplicatePaths(completedGroups)
            val candidatePaths = candidateGroups.keys
            val completedPaths = completedGroups.keys
            val ordered = completed.sortedBy { it.file }
            val workspaceMismatches = (
                candidates.filter { it.workspace != identity }.map { it.path } +
                    completed.filter { it.file.workspace != identity }.map { it.file.path }
                ).toSet()
            val candidateEvidenceMismatches = buildSet {
                candidatePaths.intersect(completedPaths).forEach { path ->
                    val candidate = candidateGroups.getValue(path).singleOrNull()
                        ?: return@forEach
                    val completedFile = completedGroups.getValue(path).singleOrNull()?.file
                        ?: return@forEach
                    when (val comparison = TopologyCandidateEvidenceMismatch.compare(
                        candidate,
                        completedFile,
                    )) {
                        TopologyCandidateEvidenceComparison.DifferentPath,
                        TopologyCandidateEvidenceComparison.Exact,
                            -> Unit
                        is TopologyCandidateEvidenceComparison.Mismatched ->
                            add(comparison.evidence)
                    }
                }
            }
            val symbols = ordered.flatMap(CompleteTopologyFile::symbols)
            val duplicateSymbols = symbols.groupBy(TopologySymbol::nodeIdentity)
                .filterValues { it.size > 1 }
                .keys
                .toCollection(linkedSetOf())
            val knownIdentities = symbols.mapTo(hashSetOf(), TopologySymbol::nodeIdentity)
            val knownSymbols = symbols.toHashSet()
            val edgeEndpoints = ordered.flatMap(CompleteTopologyFile::edges)
                .flatMap { edge -> listOf(edge.source, edge.target) }
            val missingEdgeTargets = edgeEndpoints
                .map(TopologySymbol::nodeIdentity)
                .filterNot(knownIdentities::contains)
                .toCollection(linkedSetOf())
            val mismatchedEdgeEndpoints = edgeEndpoints
                .filter { endpoint ->
                    endpoint.nodeIdentity in knownIdentities && endpoint !in knownSymbols
                }
                .toCollection(linkedSetOf())
            val failure = TopologyGenerationCoverageFailure(
                missing = candidatePaths - completedPaths,
                unexpected = completedPaths - candidatePaths,
                duplicateCandidates = duplicateCandidates,
                duplicateCompletions = duplicateCompletions,
                workspaceMismatches = workspaceMismatches,
                candidateEvidenceMismatches = candidateEvidenceMismatches,
                duplicateSymbols = duplicateSymbols,
                missingEdgeTargets = missingEdgeTargets,
                mismatchedEdgeEndpoints = mismatchedEdgeEndpoints,
            )
            if (
                failure.missing.isNotEmpty() || failure.unexpected.isNotEmpty() ||
                failure.duplicateCandidates.isNotEmpty() ||
                failure.duplicateCompletions.isNotEmpty() ||
                failure.workspaceMismatches.isNotEmpty() ||
                failure.candidateEvidenceMismatches.isNotEmpty() ||
                failure.duplicateSymbols.isNotEmpty() || failure.missingEdgeTargets.isNotEmpty() ||
                failure.mismatchedEdgeEndpoints.isNotEmpty()
            ) {
                return Refinement.Rejected(failure)
            }
            val projection = topologyCanonicalProjection(identity, ordered)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(projection.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
            return Refinement.Refined(
                CompleteTopologyGeneration(identity, ordered, TopologyGenerationDigest(digest)),
            )
        }

    }
}

internal fun topologyCanonicalProjection(
    identity: TopologyWorkspaceIdentity,
    files: List<CompleteTopologyFile>,
): String = buildString {
    appendTopologyField(identity.lease.workspaceRoot.value)
    appendTopologyField(identity.lease.generation.value.toString())
    appendTopologyField(identity.sourceState.value)
    files.forEach { complete ->
        appendTopologyField(complete.file.canonicalProjection())
        complete.symbols.forEach { appendTopologyField(it.canonicalProjection()) }
        complete.edges.forEach { appendTopologyField(it.canonicalProjection()) }
    }
}

private fun <Value> duplicatePaths(
    groups: Map<WorkspaceSourcePath, List<Value>>,
): Set<WorkspaceSourcePath> = groups.filterValues { it.size > 1 }.keys
