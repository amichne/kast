package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.ImpactExecutionStopDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument

/** The existing result grant bounds these finite terminal receipts. No source or callback body enters the output. */
internal fun recordPacketPathEvidence(paths: List<ImpactPathDocument>) {
    paths.forEachIndexed { index, path ->
        val site = path.steps.values.lastOrNull()?.compositeTarget() ?: path.producer
        println(
            "PACKET_QUERY_PATH index=$index steps=${path.steps.values.size} " +
                "file=${site.enclosing.file.value} range=${site.range.start.value}:${site.range.end.value} " +
                "role=${site.role::class.simpleName} pathDepth=${site.valuePath.values.size} " +
                "terminal=${path.terminal.receipt()}"
        )
    }
}

private fun ImpactPathTerminalDocument.receipt(): String =
    when (this) {
        is ImpactPathTerminalDocument.UnresolvedFlow -> "FLOW:$cause evidence=${evidence::class.simpleName}"
        is ImpactPathTerminalDocument.ExecutionStop -> stop.receipt()
        is ImpactPathTerminalDocument.UnresolvedRead -> rejection.receipt()
        is ImpactPathTerminalDocument.Consumer -> "CONSUMER:$outcome"
        is ImpactPathTerminalDocument.ModeledTerminal -> "MODELED_TERMINAL"
        is ImpactPathTerminalDocument.UnresolvedPeerContinuation -> "PEER:$reason"
        is ImpactPathTerminalDocument.UnresolvedBoundary -> "UNRESOLVED_BOUNDARY:$reason"
        is ImpactPathTerminalDocument.SupportedDomainEnd -> "SUPPORTED_DOMAIN_END"
        is ImpactPathTerminalDocument.ExplicitScopeExclusion -> "EXCLUDED:$cause"
    }

private fun ImpactExecutionStopDocument.receipt(): String =
    when (this) {
        is ImpactExecutionStopDocument.Cycle -> "CYCLE:${repeatedAt.value}"
        is ImpactExecutionStopDocument.CheckpointCapacity ->
            "CHECKPOINT_CAPACITY:needed=${requiredBytes.value}:available=${availableBytes.value}"
    }

private fun ImpactReadRejectionDocument.receipt(): String =
    when (this) {
        is ImpactReadRejectionDocument.Native -> "NATIVE_READ:$cause"
        is ImpactReadRejectionDocument.Contract -> "CONTRACT_READ:$cause"
    }
