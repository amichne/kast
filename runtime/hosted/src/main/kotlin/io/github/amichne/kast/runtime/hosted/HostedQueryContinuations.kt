package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.query.protocol.QueryStateStore
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal typealias HostedQueryOutcome = OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunFailure>

/** Project-free bounded detached state shared across hosted read epochs; lease checks fail closed on every resume. */
internal sealed interface HostedOutputRetention {
    data class Retained(val token: ProtocolText) : HostedOutputRetention

    data object CapacityExceeded : HostedOutputRetention

    data class Rejected(
        val cause: io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
    ) : HostedOutputRetention
}

@Service(Service.Level.PROJECT)
internal class HostedQueryContinuations : Disposable {
    private val epochs = HostedEpochStore<Active>(Active::retire)

    fun forEpoch(
        lease: io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority,
        limits: ReadLimits,
    ): Refinement<Active, io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure> =
        epochs.admit(lease) { Active(lease, limits) }

    override fun dispose() = epochs.retire()

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

        val diagnosticCheckpoints =
            io.github.amichne.kast.query.protocol.DiagnosticCheckpointStore(
                capacity = limits[ReadLimitParameter.QUERY_CONTINUATION_ENTRIES].value,
                maximumBytes = limits[ReadLimitParameter.QUERY_CONTINUATION_BYTES].value.toLong(),
                maximumCheckpointBytes = limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value.toLong(),
                ttlMillis = limits[ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS].value.toLong(),
            )

        val sourceState = HostedSourceStateStore(limits)

        fun retire() {
            sourceState.retire()
            queryState.retire()
            diagnosticCheckpoints.retire()
        }
    }
}

internal fun unavailableHostedRetention(): HostedResponse =
    HostedResponse.ReadRejected(
        io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure.STALE_REQUEST,
        io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage.RESULT_DETACHED,
    )
