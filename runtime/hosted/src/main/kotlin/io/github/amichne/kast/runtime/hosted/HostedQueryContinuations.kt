package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.protocol.QueryCheckpointStore
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal typealias HostedQueryOutcome = OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunFailure>

/** Project-free bounded detached state shared across hosted read epochs; lease checks fail closed on every resume. */
internal sealed interface HostedOutputRetention {
    data class Retained(val token: ProtocolText) : HostedOutputRetention

    data object CapacityExceeded : HostedOutputRetention

    data object EncodingRejected : HostedOutputRetention
}

@Service(Service.Level.PROJECT)
internal class HostedQueryContinuations : Disposable {
    private var active: Active? = null

    @Synchronized
    fun forEpoch(lease: SemanticReadAuthority, limits: ReadLimits): Active {
        val current = active
        if (current != null && current.lease == lease) return current
        current?.clear()
        return Active(lease, limits).also { active = it }
    }

    @Synchronized
    override fun dispose() {
        active?.clear()
        active = null
    }

    companion object {
        const val prefix = "query-output:v1:"
    }

    class Active(val lease: SemanticReadAuthority, private val limits: ReadLimits) {
        val checkpoints =
            QueryCheckpointStore(
                capacity = limits[ReadLimitParameter.QUERY_CONTINUATION_ENTRIES].value,
                maximumBytes = limits[ReadLimitParameter.QUERY_CONTINUATION_BYTES].value.toLong(),
                ttlMillis = limits[ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS].value.toLong(),
            )

        private val outputs =
            HostedOutputPages(
                CanonicalOperationWireBindings.queryRun,
                prefix,
                limits,
                normalize = { request: QueryRunRequest ->
                    request.copy(continuation = null, executionBudget = null)
                },
                unavailable =
                    QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE),
                mismatch = QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_MISMATCH),
            )

        val sourceOutputs = hostedSourceOutputPages(limits)
        val traversalOutputs = hostedTraversalOutputPages(limits)

        val relationOutputs =
            HostedOutputPages(
                CanonicalOperationWireBindings.relationRead,
                RelationContinuationDocument.OUTPUT_PREFIX,
                limits,
                normalize = { request: RelationReadRequest ->
                    request.copy(
                        position = RelationReadPositionDocument.Start,
                        executionBudget = null,
                        limit = (ProtocolCount.parse(1) as Refinement.Refined).value,
                    )
                },
                unavailable = RelationReadRejection.CONTINUATION_UNAVAILABLE,
                mismatch = RelationReadRejection.CONTINUATION_REQUEST_MISMATCH,
            )

        fun issue(
            request: QueryRunRequest,
            lease: SemanticReadAuthority,
            outcome: HostedQueryOutcome,
        ): HostedOutputRetention = outputs.issue(request, lease, outcome)

        fun restore(token: ProtocolText, request: QueryRunRequest, lease: SemanticReadAuthority): HostedQueryOutcome =
            outputs.restore(token, request, lease)

        fun clear() {
            outputs.clear()
            relationOutputs.clear()
            sourceOutputs.clear()
            traversalOutputs.clear()
            checkpoints.clear()
        }
    }
}
