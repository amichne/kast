package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class TopologySnapshotContentFailure {
    IDENTITY_MISMATCH,
    DUPLICATE_FILE,
    DUPLICATE_SYMBOL,
    MISSING_EDGE_TARGET,
    EDGE_ENDPOINT_MISMATCH,
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
         * Re-establishes one workspace identity, unique files and exact location-bearing symbols,
         * closed exact edge targets, exact manifest counts, and byte-for-byte digest equality for
         * persisted content. Compiler identity may repeat at distinct exact locations.
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
            if (symbols.map(TopologySymbol::nodeIdentity).distinct().size != symbols.size) {
                failures += TopologySnapshotContentFailure.DUPLICATE_SYMBOL
            }
            val exactIdentities = symbols.mapTo(hashSetOf(), TopologySymbol::nodeIdentity)
            val exactSymbols = symbols.toHashSet()
            val edgeEndpoints = ordered.flatMap(CompleteTopologyFile::edges)
                .flatMap { edge -> listOf(edge.source, edge.target) }
            if (edgeEndpoints.any { it.nodeIdentity !in exactIdentities }) {
                failures += TopologySnapshotContentFailure.MISSING_EDGE_TARGET
            }
            if (edgeEndpoints.any { endpoint ->
                    endpoint.nodeIdentity in exactIdentities && endpoint !in exactSymbols
                }
            ) {
                failures += TopologySnapshotContentFailure.EDGE_ENDPOINT_MISMATCH
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
    /**
     * Proof transition: `PublishedTopologySnapshot -> TopologySnapshotContentRead`.
     *
     * Loaded re-establishes exact persisted content, manifest cardinalities, and digest identity.
     * Rejected carries the closed [TopologySnapshotReadFailure]. Raw storage values may enter only
     * through the concrete persistence adapter behind this boundary.
     */
    fun read(snapshot: PublishedTopologySnapshot): TopologySnapshotContentRead
}
