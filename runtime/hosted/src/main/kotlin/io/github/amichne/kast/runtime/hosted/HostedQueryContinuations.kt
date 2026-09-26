package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.protocol.QueryStateStore
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
        val queryState =
            QueryStateStore(
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
                    when (request) {
                        is QueryRunRequest.Run -> request.copy(executionBudget = null)
                        is QueryRunRequest.Resume -> request.copy(executionBudget = null)
                        is QueryRunRequest.ReadResult ->
                            when (val output = request.output) {
                                is QueryOutputDocument.Symbols ->
                                    QueryRunRequest.ReadResult.symbols(request.result, request.cursor, output)
                                QueryOutputDocument.BindingRows ->
                                    QueryRunRequest.ReadResult.bindingRows(request.result, request.cursor)
                                QueryOutputDocument.Occurrences,
                                QueryOutputDocument.TraversalRecords ->
                                    error("An admitted retained-result request has a closed output kind")
                            }
                    }
                },
                unavailable =
                    QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE),
                mismatch = QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_MISMATCH),
            )

        val diagnosticCheckpoints =
            io.github.amichne.kast.query.protocol.DiagnosticCheckpointStore(
                capacity = limits[ReadLimitParameter.QUERY_CONTINUATION_ENTRIES].value,
                maximumBytes = limits[ReadLimitParameter.QUERY_CONTINUATION_BYTES].value.toLong(),
                maximumCheckpointBytes = limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value.toLong(),
                ttlMillis = limits[ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS].value.toLong(),
            )

        val diagnosticOutputs = hostedDiagnosticOutputPages(limits)
        val sourceOutputs = hostedSourceOutputPages(limits)

        fun issue(
            request: QueryRunRequest,
            lease: SemanticReadAuthority,
            outcome: HostedQueryOutcome,
        ): HostedOutputRetention = outputs.issue(request, lease, outcome)

        fun restore(token: QueryExecutionContinuation.Output, lease: SemanticReadAuthority): HostedQueryOutcome =
            when (val text = ProtocolText.parse(token.value)) {
                is Refinement.Refined -> outputs.restore(text.value, lease)
                is Refinement.Rejected ->
                    OperationOutcome.Rejected(
                        QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
                    )
            }

        fun clear() {
            outputs.clear()
            sourceOutputs.clear()
            queryState.clear()
            diagnosticCheckpoints.retire()
            diagnosticOutputs.clear()
        }
    }
}
