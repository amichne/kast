package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import kotlinx.coroutines.CancellationException

internal enum class McpChangeOutcome {
    STARTED,
    RETURNED,
    CANCELLED,
    FAILED,
}

internal data class McpChangeObservation(
    val phase: McpChangePhase,
    val outcome: McpChangeOutcome,
    val elapsedNanos: Long,
)

internal inline fun <T> observeMcpChange(
    phase: McpChangePhase,
    clock: () -> Long = System::nanoTime,
    publish: (McpChangeObservation) -> Unit = ::logMcpChange,
    block: () -> T,
): T {
    val started = clock()
    publish(McpChangeObservation(phase, McpChangeOutcome.STARTED, 0))
    var outcome = McpChangeOutcome.FAILED
    try {
        return block().also { outcome = McpChangeOutcome.RETURNED }
    } catch (cancelled: CancellationException) {
        outcome = McpChangeOutcome.CANCELLED
        throw cancelled
    } finally {
        publish(McpChangeObservation(phase, outcome, (clock() - started).coerceAtLeast(0)))
    }
}

internal fun logMcpChange(value: McpChangeObservation) {
    System.err.println(
        "kast_change stage=${value.phase.name} outcome=${value.outcome.name}" +
            " elapsedNanos=${value.elapsedNanos} deadlineMs=${OperationExecutionBudget.SEMANTIC_READ.operation.value}"
    )
}
