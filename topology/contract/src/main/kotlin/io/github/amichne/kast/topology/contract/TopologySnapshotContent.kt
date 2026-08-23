package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class TopologySnapshotContentFailure {
    IDENTITY_MISMATCH,
    DUPLICATE_FILE,
    DUPLICATE_SYMBOL,
    MISSING_EDGE_TARGET,
    MANIFEST_MISMATCH,
}

/** Fully detached topology content re-admitted from one published snapshot. */
class TopologySnapshotContent private constructor(
    val snapshot: PublishedTopologySnapshot,
    val files: List<CompleteTopologyFile>,
) {
    val symbols: List<TopologySymbol> = files.flatMap(CompleteTopologyFile::symbols).sorted()
    val edges: List<TopologyEdge> = files.flatMap(CompleteTopologyFile::edges).sorted()

    companion object {
        /**
         * Proof transition: `(PublishedTopologySnapshot, List<CompleteTopologyFile>) ->
         * Refinement<TopologySnapshotContent, Set<TopologySnapshotContentFailure>>`.
         *
         * Re-establishes one workspace identity, unique files and symbols, closed edge targets,
         * exact manifest counts, and byte-for-byte digest equality for persisted content.
         * [TopologySnapshotContentFailure] is the closed expected failure. Reconstructed rows may
         * enter only from the topology SQLite adapter.
         */
        fun admit(
            snapshot: PublishedTopologySnapshot,
            files: List<CompleteTopologyFile>,
        ): Refinement<TopologySnapshotContent, Set<TopologySnapshotContentFailure>> {
            val failures = linkedSetOf<TopologySnapshotContentFailure>()
            val ordered = files.sortedBy(CompleteTopologyFile::file)
            if (ordered.any { it.file.workspace != snapshot.identity }) {
                failures += TopologySnapshotContentFailure.IDENTITY_MISMATCH
            }
            if (ordered.map { it.file.path }.distinct().size != ordered.size) {
                failures += TopologySnapshotContentFailure.DUPLICATE_FILE
            }
            val symbols = ordered.flatMap(CompleteTopologyFile::symbols)
            if (symbols.map { it.evidence.compilerIdentity.value }.distinct().size != symbols.size) {
                failures += TopologySnapshotContentFailure.DUPLICATE_SYMBOL
            }
            val identities = symbols.mapTo(hashSetOf()) { it.evidence.compilerIdentity.value }
            if (ordered.flatMap(CompleteTopologyFile::edges).any { edge ->
                    edge.source.evidence.compilerIdentity.value !in identities ||
                        edge.target.evidence.compilerIdentity.value !in identities
                }
            ) {
                failures += TopologySnapshotContentFailure.MISSING_EDGE_TARGET
            }
            val projection = topologyCanonicalProjection(snapshot.identity, ordered)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(projection.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
            val manifest = snapshot.manifest
            if (
                manifest.digest.value != digest || manifest.cardinalities.files != ordered.size ||
                manifest.cardinalities.symbols != symbols.size ||
                manifest.cardinalities.edges != ordered.sumOf { it.edges.size }
            ) {
                failures += TopologySnapshotContentFailure.MANIFEST_MISMATCH
            }
            return if (failures.isEmpty()) {
                Refinement.Refined(TopologySnapshotContent(snapshot, ordered))
            } else {
                Refinement.Rejected(failures)
            }
        }
    }
}

sealed interface TopologySnapshotContentRead {
    data class Loaded(
        val content: TopologySnapshotContent,
    ) : TopologySnapshotContentRead

    data class Rejected(
        val failure: TopologySnapshotReadFailure,
    ) : TopologySnapshotContentRead
}

/** Side-effect-free detached content read from one already published snapshot. */
fun interface TopologySnapshotContentReader {
    fun read(snapshot: PublishedTopologySnapshot): TopologySnapshotContentRead
}
