package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement

enum class TopologySnapshotManifestFailure {
    NEGATIVE_COUNT,
}

/** Exact non-negative file, symbol, and edge cardinalities for one snapshot. */
@ConsistentCopyVisibility
data class TopologySnapshotCardinalities private constructor(
    val files: Int,
    val symbols: Int,
    val edges: Int,
) {
    companion object {
        fun from(generation: CompleteTopologyGeneration): TopologySnapshotCardinalities =
            TopologySnapshotCardinalities(
                generation.files.size,
                generation.symbols.size,
                generation.edges.size,
            )

        /**
         * Proof transition: `(Int, Int, Int) -> Refinement<TopologySnapshotCardinalities,
         * TopologySnapshotManifestFailure>`.
         *
         * Establishes non-negative persisted file, symbol, and edge counts as one cardinality
         * proof. [TopologySnapshotManifestFailure] is the closed expected failure. Raw counts may
         * enter only from the SQLite result-set boundary.
         */
        fun restore(
            files: Int,
            symbols: Int,
            edges: Int,
        ): Refinement<TopologySnapshotCardinalities, TopologySnapshotManifestFailure> =
            if (files < 0 || symbols < 0 || edges < 0) {
                Refinement.Rejected(TopologySnapshotManifestFailure.NEGATIVE_COUNT)
            } else {
                Refinement.Refined(TopologySnapshotCardinalities(files, symbols, edges))
            }
    }
}

/** Deterministic published topology identity and exact cardinalities. */
@ConsistentCopyVisibility
data class TopologySnapshotManifest private constructor(
    val digest: TopologyGenerationDigest,
    val cardinalities: TopologySnapshotCardinalities,
) {
    companion object {
        /**
         * Proof transition: `CompleteTopologyGeneration -> TopologySnapshotManifest`.
         *
         * Preserves the complete generation digest and its exact file, symbol, and edge counts.
         * Primitive count extraction is permitted only by protocol presentation and persistence.
         */
        fun from(generation: CompleteTopologyGeneration): TopologySnapshotManifest =
            TopologySnapshotManifest(
                generation.digest,
                TopologySnapshotCardinalities.from(generation),
            )

        /**
         * Proof transition: `(TopologyGenerationDigest, Int, Int, Int) ->
         * Refinement<TopologySnapshotManifest, TopologySnapshotManifestFailure>`.
         *
         * Establishes non-negative persisted cardinalities bound to one refined digest.
         * [TopologySnapshotManifestFailure] is the closed expected failure. Raw counts may enter
         * only from the SQLite result-set boundary.
         */
        fun restore(
            digest: TopologyGenerationDigest,
            fileCount: Int,
            symbolCount: Int,
            edgeCount: Int,
        ): Refinement<TopologySnapshotManifest, TopologySnapshotManifestFailure> =
            when (val cardinalities = TopologySnapshotCardinalities.restore(
                fileCount,
                symbolCount,
                edgeCount,
            )) {
                is Refinement.Refined -> Refinement.Refined(
                    TopologySnapshotManifest(digest, cardinalities.value),
                )
                is Refinement.Rejected -> Refinement.Rejected(cardinalities.failure)
            }
    }
}

/** Opaque proof returned only by a topology persistence adapter after durable publication. */
interface PublishedTopologySnapshot {
    val identity: TopologyWorkspaceIdentity
    val manifest: TopologySnapshotManifest
}

enum class TopologySnapshotReadFailure {
    STORAGE_UNAVAILABLE,
    CORRUPT_SNAPSHOT,
}

sealed interface TopologySnapshotEligibility {
    data class Eligible(
        val snapshot: PublishedTopologySnapshot,
    ) : TopologySnapshotEligibility

    data class Stale(
        val latest: PublishedTopologySnapshot,
    ) : TopologySnapshotEligibility

    data object Unavailable : TopologySnapshotEligibility

    data class Rejected(
        val failure: TopologySnapshotReadFailure,
    ) : TopologySnapshotEligibility
}

/** Side-effect-free eligibility lookup for an exact workspace identity. */
fun interface TopologySnapshotReader {
    fun eligible(identity: TopologyWorkspaceIdentity): TopologySnapshotEligibility
}

enum class TopologyPublicationFailure {
    STORAGE_UNAVAILABLE,
    SNAPSHOT_CONFLICT,
    CORRUPT_SNAPSHOT,
}

sealed interface TopologyPublicationResult {
    data class Published(
        val snapshot: PublishedTopologySnapshot,
    ) : TopologyPublicationResult

    data class Unchanged(
        val snapshot: PublishedTopologySnapshot,
    ) : TopologyPublicationResult

    data class Rejected(
        val failure: TopologyPublicationFailure,
    ) : TopologyPublicationResult
}

/** Sole physical publication port; production implementation belongs to `:evidence:sqlite`. */
fun interface TopologySnapshotPublisher {
    fun publish(generation: CompleteTopologyGeneration): TopologyPublicationResult
}

/** Combined read and publication port implemented in production only by `:evidence:sqlite`. */
interface TopologySnapshotStore :
    TopologySnapshotReader,
    TopologySnapshotPublisher,
    TopologySnapshotContentReader
