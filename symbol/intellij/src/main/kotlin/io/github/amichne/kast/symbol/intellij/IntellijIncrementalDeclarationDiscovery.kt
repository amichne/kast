package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import java.util.concurrent.CancellationException

/** Lexical unread partitions advance before semantic reads; a file resumes at its next PSI leaf. */
internal class IntellijIncrementalDeclarationDiscovery(
    private val scope: CompiledIntellijSearchScope,
    private val request: SymbolDiscoveryRequest,
    private val limits: ReadLimits,
    private val allowance: IntellijDeclarationDiscoveryAllowance,
    private val initialPartitions: () -> List<SemanticFilePartition>,
    private val observePartition: (SemanticFilePartition) -> IntellijDeclarationPartitionObservation,
    private val environment: () -> IntellijDiscoveryEnvironmentState,
    private val cancellationCheck: () -> Unit,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
) {
    fun execute(): IntellijNativeDiscoveryExecution {
        if (request.remainder?.matches(request) == false)
            return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT)
        val state =
            IntellijDeclarationDiscoveryState(request, limits, allowance, environment, cancellationCheck, observation)
        val started = allowance.now()
        try {
            prepare(state)
            if (state.block == null) IntellijDeclarationPartitionScanner(scope, state, observePartition).scan()
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IndexNotReadyException) {
            state.qualifications += SymbolDiscoveryQualification.DUMB_MODE_TRANSITION
            state.block = SymbolDiscoveryBlockCause.PROVIDER_UNAVAILABLE
        }
        return state.produce(started)
    }

    private fun prepare(state: IntellijDeclarationDiscoveryState) {
        if (request.remainder != null || scope.population == IntellijScopePopulation.KNOWN_EMPTY) return
        val started = allowance.now()
        observation.phase(IntellijReadPhase.DISCOVERY_INVENTORY)
        val initial = initialPartitions()
        state.inventoryNanos += state.elapsed(started)
        initial.forEach { state.frontier[it.orderingPath] = it }
        state.frontierBytes = initial.sumOf { it.detachedBytes() }
        if (state.frontier.isEmpty()) state.block = SymbolDiscoveryBlockCause.PROVIDER_UNAVAILABLE
        if (!state.retentionFits()) state.block = SymbolDiscoveryBlockCause.RETENTION_LIMIT
    }
}
