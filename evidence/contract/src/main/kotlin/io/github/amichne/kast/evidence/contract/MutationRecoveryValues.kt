package io.github.amichne.kast.evidence.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

enum class MutationPlanBindingFailure {
    INVALID,
}

/** Canonical SHA-256 identity of the exact mutation plan bound to durable recovery evidence. */
@JvmInline
value class MutationPlanBinding private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<MutationPlanBinding,
         * MutationPlanBindingFailure>`.
         *
         * Establishes a canonical lowercase SHA-256 plan identity. [MutationPlanBindingFailure]
         * is the closed expected failure. Raw text may enter only from a change-plan identity or
         * SQLite row and may be extracted only at those boundaries.
         */
        fun parse(
            raw: String,
        ): Refinement<MutationPlanBinding, MutationPlanBindingFailure> =
            if (SHA256.matches(raw)) {
                Refinement.Refined(MutationPlanBinding(raw))
            } else {
                Refinement.Rejected(MutationPlanBindingFailure.INVALID)
            }
    }
}

enum class RecoverySourcePathFailure {
    INVALID,
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
}

/** Canonical absolute source identity retained by recovery evidence. */
@JvmInline
value class RecoverySourcePath private constructor(
    val value: String,
) : Comparable<RecoverySourcePath> {
    override fun compareTo(other: RecoverySourcePath): Int = value.compareTo(other.value)

    companion object {
        /**
         * Proof transition: `String -> Refinement<RecoverySourcePath,
         * RecoverySourcePathFailure>`.
         *
         * Establishes a normalized absolute path identity. [RecoverySourcePathFailure] is the
         * closed expected failure. Raw text may enter only from an exact change target or SQLite
         * row and may be extracted only by the recovery source adapter or SQLite boundary.
         */
        fun parse(
            raw: String,
        ): Refinement<RecoverySourcePath, RecoverySourcePathFailure> {
            val path = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return Refinement.Rejected(RecoverySourcePathFailure.INVALID)
            }
            return when {
                !path.isAbsolute -> Refinement.Rejected(RecoverySourcePathFailure.NOT_ABSOLUTE)
                path.normalize() != path || path.toString() != raw ->
                    Refinement.Rejected(RecoverySourcePathFailure.NOT_NORMALIZED)
                else -> Refinement.Refined(RecoverySourcePath(raw))
            }
        }
    }
}

@JvmInline
value class RecoveryContentDigest internal constructor(
    val value: String,
)

@JvmInline
value class RecoveryEncodedContent internal constructor(
    val value: String,
)

enum class RecoveryPreimageFailure {
    DIGEST_INVALID,
    CONTENT_INVALID,
    CONTENT_DIGEST_MISMATCH,
}

/** Immutable byte-exact source preimage with its calculated content identity. */
class RecoveryPreimage private constructor(
    val digest: RecoveryContentDigest,
    val encodedContent: RecoveryEncodedContent,
) {
    /** Raw bytes are released only to an admitted source-recovery adapter. */
    fun decodeAtRecoveryBoundary(): ByteArray = Base64.getDecoder().decode(encodedContent.value)

    override fun equals(other: Any?): Boolean =
        other is RecoveryPreimage &&
            digest == other.digest &&
            encodedContent == other.encodedContent

    override fun hashCode(): Int = 31 * digest.hashCode() + encodedContent.hashCode()

    companion object {
        /**
         * Proof transition: `ByteArray -> RecoveryPreimage`.
         *
         * Establishes an immutable Base64 projection and SHA-256 identity for the exact supplied
         * bytes. There is no expected failure because every byte sequence has one canonical
         * representation. Raw bytes may enter at source observation and be extracted only by an
         * admitted recovery adapter.
         */
        fun fromBoundary(bytes: ByteArray): RecoveryPreimage = RecoveryPreimage(
            RecoveryContentDigest(sha256(bytes)),
            RecoveryEncodedContent(Base64.getEncoder().encodeToString(bytes)),
        )

        /**
         * Proof transition: `(String, String) -> Refinement<RecoveryPreimage,
         * RecoveryPreimageFailure>`.
         *
         * Establishes canonical Base64 bytes whose calculated SHA-256 equals the supplied digest.
         * [RecoveryPreimageFailure] is the closed expected failure. Raw columns may enter only at
         * the SQLite decoder and raw bytes may leave only at the recovery adapter.
         */
        fun restore(
            rawDigest: String,
            rawEncodedContent: String,
        ): Refinement<RecoveryPreimage, RecoveryPreimageFailure> {
            if (!SHA256.matches(rawDigest)) {
                return Refinement.Rejected(RecoveryPreimageFailure.DIGEST_INVALID)
            }
            val bytes = try {
                Base64.getDecoder().decode(rawEncodedContent)
            } catch (_: IllegalArgumentException) {
                return Refinement.Rejected(RecoveryPreimageFailure.CONTENT_INVALID)
            }
            if (Base64.getEncoder().encodeToString(bytes) != rawEncodedContent) {
                return Refinement.Rejected(RecoveryPreimageFailure.CONTENT_INVALID)
            }
            if (sha256(bytes) != rawDigest) {
                return Refinement.Rejected(RecoveryPreimageFailure.CONTENT_DIGEST_MISMATCH)
            }
            return Refinement.Refined(
                RecoveryPreimage(
                    RecoveryContentDigest(rawDigest),
                    RecoveryEncodedContent(rawEncodedContent),
                ),
            )
        }
    }
}

data class PlannedRecoveryWrite(
    val source: RecoverySourcePath,
    val preimage: RecoveryPreimage,
)

enum class MutationRecoveryPreparationFailure {
    EMPTY_WRITE_SET,
    DUPLICATE_SOURCE,
    NON_DETERMINISTIC_ORDER,
}

/** Exact pre-write material bound to one mutation plan before any source effect. */
class MutationRecoveryPreparation private constructor(
    val binding: MutationPlanBinding,
    plannedWrites: List<PlannedRecoveryWrite>,
) {
    val plannedWrites: List<PlannedRecoveryWrite> = plannedWrites.toList()

    companion object {
        /**
         * Proof transition: `(MutationPlanBinding, List<PlannedRecoveryWrite>) -> Refinement<
         * MutationRecoveryPreparation, MutationRecoveryPreparationFailure>`.
         *
         * Establishes a non-empty, unique, deterministically ordered write set whose exact
         * preimages are bound to one plan. [MutationRecoveryPreparationFailure] is the closed
         * expected failure. Raw content extraction remains confined to recovery and SQLite.
         */
        fun admit(
            binding: MutationPlanBinding,
            plannedWrites: List<PlannedRecoveryWrite>,
        ): Refinement<MutationRecoveryPreparation, MutationRecoveryPreparationFailure> = when {
            plannedWrites.isEmpty() ->
                Refinement.Rejected(MutationRecoveryPreparationFailure.EMPTY_WRITE_SET)
            plannedWrites.map { it.source }.distinct().size != plannedWrites.size ->
                Refinement.Rejected(MutationRecoveryPreparationFailure.DUPLICATE_SOURCE)
            plannedWrites != plannedWrites.sortedBy { it.source } ->
                Refinement.Rejected(MutationRecoveryPreparationFailure.NON_DETERMINISTIC_ORDER)
            else -> Refinement.Refined(MutationRecoveryPreparation(binding, plannedWrites))
        }
    }
}

enum class AppliedRecoveryWriteSetFailure {
    EMPTY,
    DUPLICATE_SOURCE,
    NON_DETERMINISTIC_ORDER,
    SOURCE_NOT_PLANNED,
}

/** Non-empty exact subset of planned source identities known to have been written. */
class AppliedRecoveryWriteSet private constructor(
    sources: List<RecoverySourcePath>,
) {
    val sources: List<RecoverySourcePath> = sources.toList()

    companion object {
        /**
         * Proof transition: `(List<PlannedRecoveryWrite>, List<RecoverySourcePath>) -> Refinement<
         * AppliedRecoveryWriteSet, AppliedRecoveryWriteSetFailure>`.
         *
         * Establishes a non-empty, unique, deterministically ordered subset of the exact planned
         * write identities. [AppliedRecoveryWriteSetFailure] is the closed expected failure. Raw
         * path extraction is permitted only at the source or SQLite boundary.
         */
        fun admit(
            planned: List<PlannedRecoveryWrite>,
            applied: List<RecoverySourcePath>,
        ): Refinement<AppliedRecoveryWriteSet, AppliedRecoveryWriteSetFailure> = when {
            applied.isEmpty() -> Refinement.Rejected(AppliedRecoveryWriteSetFailure.EMPTY)
            applied.distinct().size != applied.size ->
                Refinement.Rejected(AppliedRecoveryWriteSetFailure.DUPLICATE_SOURCE)
            applied != applied.sorted() ->
                Refinement.Rejected(AppliedRecoveryWriteSetFailure.NON_DETERMINISTIC_ORDER)
            applied.any { source -> planned.none { write -> write.source == source } } ->
                Refinement.Rejected(AppliedRecoveryWriteSetFailure.SOURCE_NOT_PLANNED)
            else -> Refinement.Refined(AppliedRecoveryWriteSet(applied))
        }
    }
}

internal fun StringBuilder.appendRecoveryField(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}

internal fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(bytes),
)

private val SHA256 = Regex("[0-9a-f]{64}")
