package io.github.amichne.kast.protocol.continuation

import io.github.amichne.kast.kernel.Refinement

enum class ContinuationPositiveLimitFailure {
    NOT_POSITIVE,
}

enum class ContinuationTtlFailure {
    NOT_POSITIVE,
    TOO_LARGE,
}

@JvmInline
value class ContinuationTokenLimit private constructor(val value: Int) {
    companion object {
        /**
         * Proof transition: `Int -> Refinement<ContinuationTokenLimit, ContinuationPositiveLimitFailure>`.
         *
         * Establishes a strictly positive maximum number of simultaneously owned tokens.
         */
        fun parse(raw: Int): Refinement<ContinuationTokenLimit, ContinuationPositiveLimitFailure> =
            positive(raw, ::ContinuationTokenLimit)
    }
}

@JvmInline
value class ContinuationRecordLimit private constructor(val value: Int) {
    companion object {
        /**
         * Proof transition: `Int -> Refinement<ContinuationRecordLimit, ContinuationPositiveLimitFailure>`.
         *
         * Establishes a strictly positive global cached-record bound.
         */
        fun parse(raw: Int): Refinement<ContinuationRecordLimit, ContinuationPositiveLimitFailure> =
            positive(raw, ::ContinuationRecordLimit)
    }
}

@JvmInline
value class ContinuationByteLimit private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<ContinuationByteLimit, ContinuationPositiveLimitFailure>`.
         *
         * Establishes a strictly positive global cached UTF-8 byte bound.
         */
        fun parse(raw: Long): Refinement<ContinuationByteLimit, ContinuationPositiveLimitFailure> =
            positive(raw, ::ContinuationByteLimit)
    }
}

@JvmInline
value class ContinuationPageRecordLimit private constructor(val value: Int) {
    companion object {
        /**
         * Proof transition: `Int -> Refinement<ContinuationPageRecordLimit, ContinuationPositiveLimitFailure>`.
         *
         * Establishes a strictly positive returned-record bound for one page.
         */
        fun parse(raw: Int): Refinement<ContinuationPageRecordLimit, ContinuationPositiveLimitFailure> =
            positive(raw, ::ContinuationPageRecordLimit)
    }
}

@JvmInline
value class ContinuationPageByteLimit private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<ContinuationPageByteLimit, ContinuationPositiveLimitFailure>`.
         *
         * Establishes a strictly positive returned UTF-8 byte bound for one page.
         */
        fun parse(raw: Long): Refinement<ContinuationPageByteLimit, ContinuationPositiveLimitFailure> =
            positive(raw, ::ContinuationPageByteLimit)
    }
}

@JvmInline
value class ContinuationTtlMillis private constructor(val value: Long) {
    internal val nanoseconds: Long get() = value * NANOS_PER_MILLISECOND

    companion object {
        /**
         * Proof transition: `Long -> Refinement<ContinuationTtlMillis, ContinuationTtlFailure>`.
         *
         * Establishes a positive TTL whose nanosecond form cannot overflow.
         * [ContinuationTtlFailure] is the closed expected failure. Raw time may be extracted only
         * by the continuation clock/expiry boundary.
         */
        fun parse(raw: Long): Refinement<ContinuationTtlMillis, ContinuationTtlFailure> = when {
            raw <= 0L -> Refinement.Rejected(ContinuationTtlFailure.NOT_POSITIVE)
            raw > Long.MAX_VALUE / NANOS_PER_MILLISECOND ->
                Refinement.Rejected(ContinuationTtlFailure.TOO_LARGE)
            else -> Refinement.Refined(ContinuationTtlMillis(raw))
        }
    }
}

@JvmInline
value class ContinuationByteCount internal constructor(val value: Long)

data class ContinuationStoreLimits(
    val tokens: ContinuationTokenLimit,
    val cachedRecords: ContinuationRecordLimit,
    val cachedBytes: ContinuationByteLimit,
    val ttl: ContinuationTtlMillis,
)

data class ContinuationPageBudget(
    val records: ContinuationPageRecordLimit,
    val bytes: ContinuationPageByteLimit,
)

/** Refines a raw integer into one operation-specific positive continuation limit. */
private fun <Strong> positive(
    raw: Int,
    create: (Int) -> Strong,
): Refinement<Strong, ContinuationPositiveLimitFailure> =
    if (raw > 0) Refinement.Refined(create(raw))
    else Refinement.Rejected(ContinuationPositiveLimitFailure.NOT_POSITIVE)

/** Refines a raw long into one operation-specific positive continuation limit. */
private fun <Strong> positive(
    raw: Long,
    create: (Long) -> Strong,
): Refinement<Strong, ContinuationPositiveLimitFailure> =
    if (raw > 0L) Refinement.Refined(create(raw))
    else Refinement.Rejected(ContinuationPositiveLimitFailure.NOT_POSITIVE)

private const val NANOS_PER_MILLISECOND = 1_000_000L
