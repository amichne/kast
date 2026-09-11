package io.github.amichne.kast.indexer

import io.github.amichne.kast.distribution.contract.IndexerTransportLimits
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget

@JvmInline
value class IndexerConnectionLimit private constructor(val value: Int) {
    companion object {
        fun admit(value: Int): Refinement<IndexerConnectionLimit, IndexerRequestPolicyFailure> =
            if (value in 1..IndexerTransportLimits.maximumConnections) Refinement.Refined(IndexerConnectionLimit(value))
            else Refinement.Rejected(IndexerRequestPolicyFailure.CONNECTION_LIMIT_INVALID)
    }
}

enum class IndexerRequestPolicyFailure {
    CONNECTION_LIMIT_INVALID
}

/** Process ingress limits. Tests inject admitted values; production has no environment override. */
class IndexerRequestPolicy(
    val connections: IndexerConnectionLimit,
    val frameRead: ElapsedTimeLimitMillis,
    val frameWrite: ElapsedTimeLimitMillis,
    val dispatch: ElapsedTimeLimitMillis,
    val retirement: ElapsedTimeLimitMillis,
) {
    companion object {
        val Default =
            IndexerRequestPolicy(
                connections =
                    when (val value = IndexerConnectionLimit.admit(IndexerTransportLimits.defaultConnections)) {
                        is Refinement.Refined -> value.value
                        is Refinement.Rejected -> error("Invalid fixed connection policy")
                    },
                frameRead = OperationExecutionBudget.LOCAL_QUALIFICATION,
                frameWrite = OperationExecutionBudget.LOCAL_QUALIFICATION,
                dispatch = OperationExecutionBudget.GRAPH_BUILD.operation,
                retirement = OperationExecutionBudget.LOCAL_QUALIFICATION,
            )
    }
}

enum class IndexerRequestStage {
    CONNECTION_ADMISSION,
    PEER_QUALIFICATION,
    FRAME_READ,
    SEMANTIC_ADMISSION,
    DISPATCH,
    FRAME_WRITE,
    RETIREMENT,
    TRANSPORT_CLOSE,
}

enum class IndexerRequestOutcome {
    STARTED,
    COMPLETED,
    REJECTED,
    CAPACITY_EXCEEDED,
    DEADLINE_EXCEEDED,
    CANCELLED,
    RECOVERY_REQUIRED,
}

data class IndexerRequestActivity(val stage: IndexerRequestStage, val outcome: IndexerRequestOutcome)

fun interface IndexerRequestActivitySink {
    fun observe(activity: IndexerRequestActivity)

    companion object {
        /** Bounded enum-only evidence; never request text, workspace paths, or credentials. */
        val StandardError = IndexerRequestActivitySink { activity ->
            System.err.println("kast-indexer stage=${activity.stage.name} outcome=${activity.outcome.name}")
        }
    }
}
