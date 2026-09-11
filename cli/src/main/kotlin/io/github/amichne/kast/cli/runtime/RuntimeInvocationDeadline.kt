package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget

/** One monotonic invocation allowance retained through startup and semantic transport admission. */
sealed interface RuntimeInvocationDeadline {
    data object EffectBoundary : RuntimeInvocationDeadline

    class Running
    internal constructor(
        private val limit: ElapsedTimeLimitMillis,
        private val started: Long,
        private val clock: () -> Long,
    ) : RuntimeInvocationDeadline {
        fun remaining(): Refinement<ElapsedTimeLimitMillis, WireTransportFailure> {
            val nanos = clock() - started
            if (nanos < 0) return Refinement.Rejected(WireTransportFailure.TIMED_OUT)
            val elapsed = nanos / 1_000_000 + if (nanos % 1_000_000 == 0L) 0 else 1
            return when (val remaining = ElapsedTimeLimitMillis.parse(limit.value - elapsed)) {
                is Refinement.Refined -> remaining
                is Refinement.Rejected -> Refinement.Rejected(WireTransportFailure.TIMED_OUT)
            }
        }
    }
}

internal class RuntimeInvocationBudgetPolicy(
    private val limit: (HostedRuntimeDemand) -> ElapsedTimeLimitMillis = { demand ->
        when (demand) {
            is HostedRuntimeDemand.Operation -> OperationExecutionBudget.forOperation(demand.operation).invocation
            is HostedRuntimeDemand.ChangePlan -> OperationExecutionBudget.SEMANTIC_READ.invocation
            HostedRuntimeDemand.Lifecycle -> OperationExecutionBudget.WORKSPACE_READINESS
        }
    },
    private val clock: () -> Long = System::nanoTime,
) {
    fun begin(demand: HostedRuntimeDemand): RuntimeInvocationDeadline.Running =
        RuntimeInvocationDeadline.Running(limit(demand), clock(), clock)
}

/** The actual coordinator receipt supplies the bootstrap attempt used by the wire handshake. */
sealed interface RuntimeWireAuthority {
    data object EffectBoundary : RuntimeWireAuthority

    data class Qualified(val identity: io.github.amichne.kast.distribution.contract.WireRuntimeIdentity) :
        RuntimeWireAuthority
}
