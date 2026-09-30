package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryActiveInput
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryInputRevision
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRemainder
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.candidateOrder
import java.nio.file.Path

internal fun progressiveQueryDiscoveryCandidates(
    names: List<String>,
    examined: MutableList<Int>,
): SymbolDiscoveryOperations = SymbolDiscoveryOperations { child ->
    val start = child.remainder?.nextOffset?.value ?: 0
    val end = minOf(start + child.budget.resources.resultLimit.value, names.size)
    val candidates =
        (start until end).map { position ->
            examined += position
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.CLASS,
                    names[position],
                    child.scope.lease,
                    Path.of("/workspace/services/payments/PaymentService.kt"),
                    "file:///workspace/services/payments/PaymentService.kt",
                    position,
                )
                .refined()
        }
    val batch = progressiveBatch(child, candidates)
    SymbolDiscoveryResult.Discovered(
        if (end == names.size) SymbolDiscoveryOutcome.Complete(batch)
        else
            SymbolDiscoveryOutcome.Qualified(
                batch,
                io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications.from(
                        setOf(SymbolDiscoveryQualification.RESULT_LIMIT_REACHED)
                    )
                    .refined(),
                SymbolDiscoveryProgress.Resumable(
                    SymbolDiscoveryRemainder.fromPendingInput(
                            child,
                            emptyList(),
                            SymbolDiscoveryActiveInput.Scanning(
                                candidates.first().location.file as SymbolDiscoveryFileIdentity.Workspace,
                                SymbolDiscoverySourceOffset.parse(end).refined(),
                            ),
                            SymbolDiscoveryInputRevision.parse(end.toLong()).refined(),
                            discoveredFiles = SymbolDiscoveryWorkCount.parse(1).refined(),
                        )
                        .refined()
                ),
            )
    )
}

private fun progressiveBatch(
    child: io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest,
    candidates: List<SymbolDiscoveryCandidate>,
): SymbolDiscoveryBatch =
    SymbolDiscoveryBatch.create(
            child,
            candidates.sortedWith(child.candidateOrder()),
            SymbolDiscoveryByteCount.parse(candidates.sumOf { it.projectedUtf8Size().value }).refined(),
            SymbolDiscoveryWorkCount.parse(candidates.size.toLong()).refined(),
            SymbolDiscoveryTimings(
                SymbolDiscoveryElapsedNanoseconds.Zero,
                SymbolDiscoveryElapsedNanoseconds.Zero,
            ),
        )
        .refined()

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
