package io.github.amichne.kast.evidence.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.nio.charset.StandardCharsets

private const val SHA_256_HEX_LENGTH = 64
private const val MAX_STAGE_VERSION_LENGTH = 128
private const val MAX_CANDIDATE_SHARDS_PER_BATCH = 128
private const val MAX_CANDIDATE_SHARD_BYTES = 1024 * 1024
private const val MAX_CANDIDATE_BATCH_BYTES = 8 * 1024 * 1024

enum class EvidenceCandidateEnvironmentFailure {
    NOT_CANONICAL_SHA_256,
}

/** Exact minimum semantic-environment identity required to resume candidate evidence. */
@JvmInline
value class EvidenceCandidateEnvironment private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<EvidenceCandidateEnvironment,
         * EvidenceCandidateEnvironmentFailure>`.
         *
         * Establishes a canonical lowercase SHA-256 compiler/build environment identity.
         * [EvidenceCandidateEnvironmentFailure] is the closed expected failure. Raw text may
         * enter only at environment capture or persistence boundaries.
         */
        fun refine(
            raw: String,
        ): Refinement<EvidenceCandidateEnvironment, EvidenceCandidateEnvironmentFailure> =
            if (raw.length == SHA_256_HEX_LENGTH && raw.all { it in '0'..'9' || it in 'a'..'f' }) {
                Refinement.Refined(EvidenceCandidateEnvironment(raw))
            } else {
                Refinement.Rejected(EvidenceCandidateEnvironmentFailure.NOT_CANONICAL_SHA_256)
            }
    }
}

enum class EvidenceCandidateStageVersionFailure {
    BLANK,
    NOT_TRIMMED,
    CONTROL_CHARACTER,
    TOO_LONG,
}

/** Version identity of the deterministic stage producer for one candidate shard. */
@JvmInline
value class EvidenceCandidateStageVersion private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<EvidenceCandidateStageVersion,
         * EvidenceCandidateStageVersionFailure>`.
         *
         * Establishes a bounded, printable, non-blank stage version.
         * [EvidenceCandidateStageVersionFailure] is the closed expected failure. Raw text may
         * enter only at producer or persistence boundaries.
         */
        fun refine(
            raw: String,
        ): Refinement<EvidenceCandidateStageVersion, EvidenceCandidateStageVersionFailure> = when {
            raw.isBlank() -> Refinement.Rejected(EvidenceCandidateStageVersionFailure.BLANK)
            raw != raw.trim() -> Refinement.Rejected(EvidenceCandidateStageVersionFailure.NOT_TRIMMED)
            raw.any(Char::isISOControl) ->
                Refinement.Rejected(EvidenceCandidateStageVersionFailure.CONTROL_CHARACTER)
            raw.length > MAX_STAGE_VERSION_LENGTH ->
                Refinement.Rejected(EvidenceCandidateStageVersionFailure.TOO_LONG)
            else -> Refinement.Refined(EvidenceCandidateStageVersion(raw))
        }
    }
}

sealed interface EvidenceCandidatePayloadFailure {
    data object Empty : EvidenceCandidatePayloadFailure

    data class TooLarge(val actualBytes: Int, val maximumBytes: Int) : EvidenceCandidatePayloadFailure
}

/** Immutable serialized evidence for one file-stage candidate. */
@JvmInline
value class EvidenceCandidatePayload private constructor(val value: String) {
    val utf8ByteCount: Int
        get() = value.toByteArray(StandardCharsets.UTF_8).size

    companion object {
        /**
         * Proof transition: `String -> Refinement<EvidenceCandidatePayload,
         * EvidenceCandidatePayloadFailure>`.
         *
         * Establishes a non-empty payload bounded to one MiB.
         * [EvidenceCandidatePayloadFailure] is the closed expected failure. Raw extraction is
         * permitted only at serializer, persistence, and deserializer boundaries.
         */
        fun refine(raw: String): Refinement<EvidenceCandidatePayload, EvidenceCandidatePayloadFailure> {
            if (raw.isEmpty()) return Refinement.Rejected(EvidenceCandidatePayloadFailure.Empty)
            val bytes = raw.toByteArray(StandardCharsets.UTF_8).size
            return if (bytes > MAX_CANDIDATE_SHARD_BYTES) {
                Refinement.Rejected(EvidenceCandidatePayloadFailure.TooLarge(bytes, MAX_CANDIDATE_SHARD_BYTES))
            } else {
                Refinement.Refined(EvidenceCandidatePayload(raw))
            }
        }
    }
}

data class EvidenceCandidateShard(
    val path: WorkspaceSourcePath,
    val contentHash: WorkspaceSourceContentHash,
    val stageVersion: EvidenceCandidateStageVersion,
    val payload: EvidenceCandidatePayload,
)

sealed interface EvidenceCandidateBatchFailure {
    data object Empty : EvidenceCandidateBatchFailure

    data class TooManyShards(val actual: Int, val maximum: Int) : EvidenceCandidateBatchFailure

    data class TooManyBytes(val actual: Int, val maximum: Int) : EvidenceCandidateBatchFailure

    data class DuplicatePath(val path: WorkspaceSourcePath) : EvidenceCandidateBatchFailure
}

sealed interface EvidenceCandidateBatchResolution {
    data class Refined(val batch: EvidenceCandidateBatch) : EvidenceCandidateBatchResolution

    data class Rejected(val failure: EvidenceCandidateBatchFailure) : EvidenceCandidateBatchResolution
}

/** Non-empty, path-unique, count- and byte-bounded candidate checkpoint batch. */
class EvidenceCandidateBatch private constructor(shards: List<EvidenceCandidateShard>) {
    val shards: List<EvidenceCandidateShard> = shards.toList()

    companion object {
        /**
         * Proof transition: `List<EvidenceCandidateShard> -> EvidenceCandidateBatchResolution`.
         *
         * Establishes a non-empty batch of at most 128 path-unique shards and at most eight MiB.
         * Rejection is closed [EvidenceCandidateBatchFailure] data. Raw collection size is
         * observed only at this batching boundary.
         */
        fun refine(shards: List<EvidenceCandidateShard>): EvidenceCandidateBatchResolution {
            if (shards.isEmpty()) {
                return EvidenceCandidateBatchResolution.Rejected(EvidenceCandidateBatchFailure.Empty)
            }
            if (shards.size > MAX_CANDIDATE_SHARDS_PER_BATCH) {
                return EvidenceCandidateBatchResolution.Rejected(
                    EvidenceCandidateBatchFailure.TooManyShards(shards.size, MAX_CANDIDATE_SHARDS_PER_BATCH),
                )
            }
            val duplicate = shards.groupBy(EvidenceCandidateShard::path)
                .entries
                .firstOrNull { (_, matches) -> matches.size > 1 }
            if (duplicate != null) {
                return EvidenceCandidateBatchResolution.Rejected(
                    EvidenceCandidateBatchFailure.DuplicatePath(duplicate.key),
                )
            }
            val bytes = shards.sumOf { shard -> shard.payload.utf8ByteCount }
            if (bytes > MAX_CANDIDATE_BATCH_BYTES) {
                return EvidenceCandidateBatchResolution.Rejected(
                    EvidenceCandidateBatchFailure.TooManyBytes(bytes, MAX_CANDIDATE_BATCH_BYTES),
                )
            }
            return EvidenceCandidateBatchResolution.Refined(
                EvidenceCandidateBatch(shards.sortedBy { shard -> shard.path.value }),
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is EvidenceCandidateBatch && shards == other.shards

    override fun hashCode(): Int = shards.hashCode()

    override fun toString(): String = "EvidenceCandidateBatch(shards=${shards.size})"
}
