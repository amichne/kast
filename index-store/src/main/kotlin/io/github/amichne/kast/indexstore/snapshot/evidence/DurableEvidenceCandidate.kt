package io.github.amichne.kast.indexstore.snapshot.evidence

import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val SHA_256_HEX_LENGTH = 64
private const val MAX_STAGE_VERSION_LENGTH = 128
private const val MAX_CANDIDATE_SHARDS_PER_BATCH = 128
private const val MAX_CANDIDATE_SHARD_BYTES = 1024 * 1024
private const val MAX_CANDIDATE_BATCH_BYTES = 8 * 1024 * 1024

sealed interface EvidenceValueRefinement<out Value, out Failure> {
    data class Refined<Value>(val value: Value) : EvidenceValueRefinement<Value, Nothing>

    data class Rejected<Failure>(val failure: Failure) : EvidenceValueRefinement<Nothing, Failure>
}

enum class CandidateSha256Failure {
    NOT_CANONICAL_SHA_256,
}

/** Exact semantic-environment identity used to decide whether a candidate may resume. */
@JvmInline
value class CandidateEnvironmentFingerprint private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> EvidenceValueRefinement<CandidateEnvironmentFingerprint,
         * CandidateSha256Failure>`.
         *
         * Establishes a canonical lowercase SHA-256 identity for the minimum compiler/build
         * environment required by a candidate. Rejection is closed [CandidateSha256Failure]
         * data. Raw text may enter only at environment capture or SQLite boundaries.
         */
        fun refine(
            raw: String,
        ): EvidenceValueRefinement<CandidateEnvironmentFingerprint, CandidateSha256Failure> =
            refineSha256(raw, ::CandidateEnvironmentFingerprint)
    }
}

/** Exact source-content identity carried by one durable candidate shard. */
@JvmInline
value class CandidateContentHash private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> EvidenceValueRefinement<CandidateContentHash,
         * CandidateSha256Failure>`.
         *
         * Establishes a canonical lowercase SHA-256 file-content identity. Rejection is closed
         * [CandidateSha256Failure] data. Raw text may enter only at hashing or SQLite boundaries.
         */
        fun refine(raw: String): EvidenceValueRefinement<CandidateContentHash, CandidateSha256Failure> =
            refineSha256(raw, ::CandidateContentHash)
    }
}

enum class CandidateShardPathFailure {
    BLANK,
    INVALID,
    ABSOLUTE,
    NOT_NORMALIZED,
    ESCAPES_WORKSPACE,
}

/** Normalized workspace-relative identity of one candidate shard. */
@JvmInline
value class CandidateShardPath private constructor(val value: String) : Comparable<CandidateShardPath> {
    companion object {
        /**
         * Proof transition: `String -> EvidenceValueRefinement<CandidateShardPath,
         * CandidateShardPathFailure>`.
         *
         * Establishes a non-blank normalized relative path that cannot escape the admitted
         * workspace. Rejection is closed [CandidateShardPathFailure] data. Raw path text may
         * enter only at workspace-adapter or SQLite boundaries.
         */
        fun refine(raw: String): EvidenceValueRefinement<CandidateShardPath, CandidateShardPathFailure> {
            if (raw.isBlank()) return EvidenceValueRefinement.Rejected(CandidateShardPathFailure.BLANK)
            val path = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return EvidenceValueRefinement.Rejected(CandidateShardPathFailure.INVALID)
            }
            if (path.isAbsolute) return EvidenceValueRefinement.Rejected(CandidateShardPathFailure.ABSOLUTE)
            if (path.any { segment -> segment.toString() == ".." }) {
                return EvidenceValueRefinement.Rejected(CandidateShardPathFailure.ESCAPES_WORKSPACE)
            }
            val normalized = path.normalize()
            val canonical = normalized.joinToString("/") { segment -> segment.toString() }
            if (canonical != raw || canonical == ".") {
                return EvidenceValueRefinement.Rejected(CandidateShardPathFailure.NOT_NORMALIZED)
            }
            return EvidenceValueRefinement.Refined(CandidateShardPath(canonical))
        }
    }

    override fun compareTo(other: CandidateShardPath): Int = value.compareTo(other.value)
}

enum class CandidateStageVersionFailure {
    BLANK,
    NOT_TRIMMED,
    CONTROL_CHARACTER,
    TOO_LONG,
}

/** Version of the deterministic producer for one candidate shard. */
@JvmInline
value class CandidateStageVersion private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> EvidenceValueRefinement<CandidateStageVersion,
         * CandidateStageVersionFailure>`.
         *
         * Establishes a bounded, printable, non-blank producer version. Rejection is closed
         * [CandidateStageVersionFailure] data. Raw text may enter only at producer or SQLite
         * boundaries.
         */
        fun refine(raw: String): EvidenceValueRefinement<CandidateStageVersion, CandidateStageVersionFailure> =
            when {
                raw.isBlank() -> EvidenceValueRefinement.Rejected(CandidateStageVersionFailure.BLANK)
                raw != raw.trim() -> EvidenceValueRefinement.Rejected(CandidateStageVersionFailure.NOT_TRIMMED)
                raw.any(Char::isISOControl) ->
                    EvidenceValueRefinement.Rejected(CandidateStageVersionFailure.CONTROL_CHARACTER)
                raw.length > MAX_STAGE_VERSION_LENGTH ->
                    EvidenceValueRefinement.Rejected(CandidateStageVersionFailure.TOO_LONG)
                else -> EvidenceValueRefinement.Refined(CandidateStageVersion(raw))
            }
    }
}

sealed interface CandidateShardPayloadFailure {
    data object Empty : CandidateShardPayloadFailure

    data class TooLarge(val actualBytes: Int, val maximumBytes: Int) : CandidateShardPayloadFailure
}

/** Immutable serialized evidence for one file-stage candidate. */
@JvmInline
value class CandidateShardPayload private constructor(val value: String) {
    val utf8ByteCount: Int
        get() = value.toByteArray(StandardCharsets.UTF_8).size

    companion object {
        /**
         * Proof transition: `String -> EvidenceValueRefinement<CandidateShardPayload,
         * CandidateShardPayloadFailure>`.
         *
         * Establishes a non-empty payload bounded to one MiB. Rejection is closed
         * [CandidateShardPayloadFailure] data. Raw extraction is permitted only at serializer,
         * SQLite, and deserializer boundaries.
         */
        fun refine(raw: String): EvidenceValueRefinement<CandidateShardPayload, CandidateShardPayloadFailure> {
            if (raw.isEmpty()) return EvidenceValueRefinement.Rejected(CandidateShardPayloadFailure.Empty)
            val bytes = raw.toByteArray(StandardCharsets.UTF_8).size
            return if (bytes > MAX_CANDIDATE_SHARD_BYTES) {
                EvidenceValueRefinement.Rejected(
                    CandidateShardPayloadFailure.TooLarge(bytes, MAX_CANDIDATE_SHARD_BYTES),
                )
            } else {
                EvidenceValueRefinement.Refined(CandidateShardPayload(raw))
            }
        }
    }
}

data class DurableEvidenceCandidateShard(
    val path: CandidateShardPath,
    val contentHash: CandidateContentHash,
    val stageVersion: CandidateStageVersion,
    val payload: CandidateShardPayload,
)

sealed interface DurableEvidenceCandidateBatchFailure {
    data object Empty : DurableEvidenceCandidateBatchFailure

    data class TooManyShards(val actual: Int, val maximum: Int) : DurableEvidenceCandidateBatchFailure

    data class TooManyBytes(val actual: Int, val maximum: Int) : DurableEvidenceCandidateBatchFailure

    data class DuplicatePath(val path: CandidateShardPath) : DurableEvidenceCandidateBatchFailure
}

sealed interface DurableEvidenceCandidateBatchResolution {
    data class Resolved(val batch: DurableEvidenceCandidateBatch) : DurableEvidenceCandidateBatchResolution

    data class Rejected(
        val failure: DurableEvidenceCandidateBatchFailure,
    ) : DurableEvidenceCandidateBatchResolution
}

/** Non-empty, path-unique, count- and byte-bounded candidate checkpoint batch. */
class DurableEvidenceCandidateBatch private constructor(shards: List<DurableEvidenceCandidateShard>) {
    val shards: List<DurableEvidenceCandidateShard> = shards.toList()

    companion object {
        /**
         * Proof transition: `List<DurableEvidenceCandidateShard> ->
         * DurableEvidenceCandidateBatchResolution`.
         *
         * Establishes a non-empty batch of at most 128 path-unique shards and at most eight MiB.
         * Rejection is closed [DurableEvidenceCandidateBatchFailure] data. Raw collection size is
         * observed only at this batching boundary.
         */
        fun refine(shards: List<DurableEvidenceCandidateShard>): DurableEvidenceCandidateBatchResolution {
            if (shards.isEmpty()) {
                return DurableEvidenceCandidateBatchResolution.Rejected(
                    DurableEvidenceCandidateBatchFailure.Empty,
                )
            }
            if (shards.size > MAX_CANDIDATE_SHARDS_PER_BATCH) {
                return DurableEvidenceCandidateBatchResolution.Rejected(
                    DurableEvidenceCandidateBatchFailure.TooManyShards(
                        shards.size,
                        MAX_CANDIDATE_SHARDS_PER_BATCH,
                    ),
                )
            }
            val duplicate = shards.groupBy(DurableEvidenceCandidateShard::path)
                .entries
                .firstOrNull { (_, matches) -> matches.size > 1 }
            if (duplicate != null) {
                return DurableEvidenceCandidateBatchResolution.Rejected(
                    DurableEvidenceCandidateBatchFailure.DuplicatePath(duplicate.key),
                )
            }
            val bytes = shards.sumOf { shard -> shard.payload.utf8ByteCount }
            if (bytes > MAX_CANDIDATE_BATCH_BYTES) {
                return DurableEvidenceCandidateBatchResolution.Rejected(
                    DurableEvidenceCandidateBatchFailure.TooManyBytes(bytes, MAX_CANDIDATE_BATCH_BYTES),
                )
            }
            return DurableEvidenceCandidateBatchResolution.Resolved(
                DurableEvidenceCandidateBatch(shards.sortedBy(DurableEvidenceCandidateShard::path)),
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is DurableEvidenceCandidateBatch && shards == other.shards

    override fun hashCode(): Int = shards.hashCode()

    override fun toString(): String = "DurableEvidenceCandidateBatch(shards=${shards.size})"
}

private fun <Value> refineSha256(
    raw: String,
    construct: (String) -> Value,
): EvidenceValueRefinement<Value, CandidateSha256Failure> =
    if (raw.length == SHA_256_HEX_LENGTH && raw.all { it in '0'..'9' || it in 'a'..'f' }) {
        EvidenceValueRefinement.Refined(construct(raw))
    } else {
        EvidenceValueRefinement.Rejected(CandidateSha256Failure.NOT_CANONICAL_SHA_256)
    }
