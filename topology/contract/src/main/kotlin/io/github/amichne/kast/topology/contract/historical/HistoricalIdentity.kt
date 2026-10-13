package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement

enum class HistoricalValueFailure {
    INVALID_TEXT,
    INVALID_DIGEST,
    INVALID_GIT_OBJECT,
}

@JvmInline
value class HistoricalName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<HistoricalName, HistoricalValueFailure> =
            if (validHistoricalText(raw)) Refinement.Refined(HistoricalName(raw))
            else Refinement.Rejected(HistoricalValueFailure.INVALID_TEXT)
    }
}

@JvmInline
value class HistoricalRepositoryId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<HistoricalRepositoryId, HistoricalValueFailure> =
            if (validHistoricalText(raw) && ':' in raw && !raw.startsWith('/'))
                Refinement.Refined(HistoricalRepositoryId(raw))
            else Refinement.Rejected(HistoricalValueFailure.INVALID_TEXT)
    }
}

@JvmInline
value class HistoricalModuleId private constructor(val value: String) : Comparable<HistoricalModuleId> {
    override fun compareTo(other: HistoricalModuleId): Int = value.compareTo(other.value)

    companion object {
        fun parse(raw: String): Refinement<HistoricalModuleId, HistoricalValueFailure> =
            if (validHistoricalText(raw)) Refinement.Refined(HistoricalModuleId(raw))
            else Refinement.Rejected(HistoricalValueFailure.INVALID_TEXT)
    }
}

@JvmInline
value class GitObjectId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<GitObjectId, HistoricalValueFailure> =
            if (raw.length in setOf(SHA1_HEX_LENGTH, SHA256_HEX_LENGTH) && raw.all { it in '0'..'9' || it in 'a'..'f' })
                Refinement.Refined(GitObjectId(raw))
            else Refinement.Rejected(HistoricalValueFailure.INVALID_GIT_OBJECT)
    }
}

@JvmInline
value class HistoricalDigest private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<HistoricalDigest, HistoricalValueFailure> =
            if (raw.length == SHA256_HEX_LENGTH && raw.all { it in '0'..'9' || it in 'a'..'f' })
                Refinement.Refined(HistoricalDigest(raw))
            else Refinement.Rejected(HistoricalValueFailure.INVALID_DIGEST)
    }
}

data class HistoricalRevision(val repository: HistoricalRepositoryId, val commit: GitObjectId, val tree: GitObjectId)

/** Opaque content identities include model order, resolution bytes, generated inputs, and compiler configuration. */
data class HistoricalContext(
    val model: HistoricalDigest,
    val resolution: HistoricalDigest,
    val compiler: HistoricalDigest,
)

enum class HistoricalProfile {
    STRUCTURE_V1,
    REFERENCES_V1,
}

data class HistoricalHeader(
    val revision: HistoricalRevision,
    val context: HistoricalContext,
    val profile: HistoricalProfile,
    val producer: HistoricalName,
)

private fun validHistoricalText(raw: String): Boolean =
    raw.isNotBlank() && raw.length <= MAXIMUM_TEXT_LENGTH && raw.none(Char::isISOControl)

private const val SHA1_HEX_LENGTH = 40
private const val SHA256_HEX_LENGTH = 64
private const val MAXIMUM_TEXT_LENGTH = 16384
