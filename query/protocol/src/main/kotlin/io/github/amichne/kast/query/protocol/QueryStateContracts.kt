package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.UUID

sealed interface QueryCheckpointRestoration {
    data class Restored(val request: QueryRunRequest.Run, val checkpoint: QueryCheckpoint) : QueryCheckpointRestoration

    data object Unavailable : QueryCheckpointRestoration

    data object Mismatch : QueryCheckpointRestoration
}

sealed interface QueryCheckpointIssuance {
    data class Issued(val token: QueryExecutionContinuation.Pipeline) : QueryCheckpointIssuance

    data object CapacityExceeded : QueryCheckpointIssuance

    data object Unavailable : QueryCheckpointIssuance
}

sealed interface QueryResultRestoration {
    data class Restored(
        val request: QueryRunRequest.Run,
        val result: QueryRetainedResult,
        val rowIds: List<QueryResultRowReference>,
    ) : QueryResultRestoration

    data object Unavailable : QueryResultRestoration

    data object StaleBasis : QueryResultRestoration
}

sealed interface QueryResultIssuance {
    data object Unavailable : QueryResultIssuance

    data class Issued(val reference: QueryResultReference, val rowIds: List<QueryResultRowReference>) :
        QueryResultIssuance

    data object CapacityExceeded : QueryResultIssuance
}

typealias QueryPublishedPage = OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection>

/** Actual hosted encoding supplies its bound; pure callers retain the already admitted page reservation. */
sealed interface QueryPublicationPageCharge {
    data object Reserved : QueryPublicationPageCharge

    class Encoded private constructor(val page: QueryPublishedPage, val encodedBytes: QueryRetentionByteCount) :
        QueryPublicationPageCharge {
        companion object {
            /** The successful owning encoder supplies the measured UTF-8 document bound for this exact page. */
            fun fromEncoding(page: QueryPublishedPage, encodedBytes: QueryRetentionByteCount): Encoded =
                Encoded(page, encodedBytes)
        }
    }
}

/** Only this store can issue an attempt capability for an initial request or one checkpoint. */
class QueryExecutionClaim
internal constructor(
    internal val origin: QueryExecutionOrigin,
    internal val identity: UUID,
)

internal sealed interface QueryExecutionOrigin {
    data class Initial(
        val lease: SemanticReadAuthority,
        val createdAt: Long,
        val inputs: Set<QueryStateKey>,
    ) : QueryExecutionOrigin

    data class Producer(val token: QueryExecutionContinuation) : QueryExecutionOrigin

    data class Replay(val token: QueryExecutionContinuation, val page: QueryPublishedPage, val createdAt: Long) :
        QueryExecutionOrigin
}

/** Pins share already retained keys; the bounded set structure still consumes the owner's byte quota. */
internal fun QueryExecutionOrigin.accountedClaimBytes(): Long =
    TRANSIENT_CLAIM_BASE_BYTES +
        when (this) {
            is QueryExecutionOrigin.Initial -> inputs.size.toLong() * PINNED_INPUT_STRUCTURE_BYTES
            is QueryExecutionOrigin.Producer,
            is QueryExecutionOrigin.Replay -> 0L
        }

internal const val TRANSIENT_CLAIM_BASE_BYTES = 256L
private const val PINNED_INPUT_STRUCTURE_BYTES = 128L

internal sealed interface QueryInitialAcquisition {
    data class Acquired(val claim: QueryExecutionClaim) : QueryInitialAcquisition

    data object Unavailable : QueryInitialAcquisition

    data object CapacityExceeded : QueryInitialAcquisition
}

internal sealed interface QueryCheckpointAcquisition {
    data class Acquired(
        val claim: QueryExecutionClaim,
        val request: QueryRunRequest.Run,
        val checkpoint: QueryCheckpoint,
    ) : QueryCheckpointAcquisition

    data class Published(val claim: QueryExecutionClaim, val page: QueryPublishedPage) : QueryCheckpointAcquisition

    data object InUse : QueryCheckpointAcquisition

    data object Unavailable : QueryCheckpointAcquisition

    data object Mismatch : QueryCheckpointAcquisition

    data object CapacityExceeded : QueryCheckpointAcquisition
}

sealed interface QueryOutputAcquisition {
    data class Acquired(val claim: QueryExecutionClaim, val page: QueryPublishedPage) : QueryOutputAcquisition

    data class Published(val claim: QueryExecutionClaim, val page: QueryPublishedPage) : QueryOutputAcquisition

    data object InUse : QueryOutputAcquisition

    data object Unavailable : QueryOutputAcquisition

    data object Mismatch : QueryOutputAcquisition

    data object CapacityExceeded : QueryOutputAcquisition
}

sealed interface QueryOutputIssuance {
    data class Issued(val token: QueryExecutionContinuation.Output) : QueryOutputIssuance

    data object Unavailable : QueryOutputIssuance

    data object CapacityExceeded : QueryOutputIssuance

    data object EncodingRejected : QueryOutputIssuance

    data object NonAdvancing : QueryOutputIssuance

    data object PublishedPageImmutable : QueryOutputIssuance
}

sealed interface QueryPublicationCommit {
    data object Committed : QueryPublicationCommit

    data class Rejected(val failure: QueryPublicationFailure) : QueryPublicationCommit
}

/** Accounting estimate under the store quota, distinct from a process heap measurement. */
data class QueryRetentionMeasurements(
    val retainedBytes: QueryRetentionByteCount,
    val highWaterBytes: QueryRetentionByteCount,
    val retainedEntries: io.github.amichne.kast.query.contract.QueryCount,
)

@JvmInline
value class QueryRetentionByteCount private constructor(val value: Long) {
    companion object {
        fun parse(value: Long): Refinement<QueryRetentionByteCount, QueryRetentionMeasureFailure> =
            if (value < 0L) Refinement.Rejected(QueryRetentionMeasureFailure.NEGATIVE_BYTES)
            else Refinement.Refined(QueryRetentionByteCount(value))

        internal fun measured(value: Long): QueryRetentionByteCount =
            when (val parsed = parse(value)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> error("Bounded retention accounting cannot be negative")
            }
    }
}

enum class QueryRetentionMeasureFailure {
    NEGATIVE_BYTES
}

/** Finite store publication failures remain distinct from semantic authority failures. */
enum class QueryPublicationFailure {
    OWNER_RETIRED,
    CLAIM_UNAVAILABLE,
    EXPIRED,
    DEPENDENCY_UNAVAILABLE,
    PUBLISHED_PAGE_MISMATCH,
    NON_ADVANCING_SUCCESSOR,
}
