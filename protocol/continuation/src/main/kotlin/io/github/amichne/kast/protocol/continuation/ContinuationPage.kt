package io.github.amichne.kast.protocol.continuation

import java.lang.System as JavaSystem

enum class ContinuationIssueFailure {
    STORE_CLOSED,
    EMPTY_STATE,
    TOKEN_LIMIT_REACHED,
    RECORD_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    CANCELLED,
    TOKEN_COLLISION,
    TOKEN_ISSUER_FAILURE,
}

enum class ContinuationAccessFailure {
    STORE_CLOSED,
    UNKNOWN_TOKEN,
    WRONG_WORKSPACE_ROOT,
    GENERATION_CHANGED,
    NORMALIZED_REQUEST_CHANGED,
    SCOPE_CHANGED,
    ORDER_CHANGED,
    RESOURCE_OWNER_CHANGED,
    EXPIRED,
    CANCELLED,
    TOKEN_COLLISION,
    TOKEN_ISSUER_FAILURE,
    PAGE_BYTE_LIMIT_TOO_SMALL,
}

enum class ContinuationCancellationStatus {
    CONTINUE,
    CANCELLED,
}

fun interface ContinuationCancellationProbe {
    /** Returns the current closed cancellation state at a bounded continuation boundary. */
    fun status(): ContinuationCancellationStatus

    companion object {
        val Never: ContinuationCancellationProbe =
            ContinuationCancellationProbe { ContinuationCancellationStatus.CONTINUE }
    }
}

@JvmInline
value class ContinuationResumePosition internal constructor(
    val value: Long,
)

@JvmInline
internal value class ContinuationIssuedAtNanos(
    val value: Long,
)

@ConsistentCopyVisibility
data class ContinuationPageSegment internal constructor(
    val position: ContinuationResumePosition,
    val records: List<DetachedContinuationRecord>,
    val encodedBytes: ContinuationByteCount,
)

sealed interface ContinuationPage {
    val segment: ContinuationPageSegment

    data class Complete(
        override val segment: ContinuationPageSegment,
    ) : ContinuationPage

    data class More(
        override val segment: ContinuationPageSegment,
        val nextToken: ContinuationToken,
    ) : ContinuationPage
}

sealed interface ContinuationIssueResult {
    data class Issued(val token: ContinuationToken) : ContinuationIssueResult

    data class Rejected(val failure: ContinuationIssueFailure) : ContinuationIssueResult
}

sealed interface ContinuationResumeResult {
    data class Resumed(val page: ContinuationPage) : ContinuationResumeResult

    data class Rejected(val failure: ContinuationAccessFailure) : ContinuationResumeResult
}

sealed interface ContinuationInvalidationResult {
    data object Invalidated : ContinuationInvalidationResult

    data class Rejected(val failure: ContinuationAccessFailure) : ContinuationInvalidationResult
}

fun interface ContinuationClock {
    /** Reads raw monotonic nanoseconds only at the store's TTL boundary. */
    fun nowNanos(): Long

    companion object {
        val System: ContinuationClock = ContinuationClock(JavaSystem::nanoTime)
    }
}
