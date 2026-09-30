package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryActiveInput
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import java.nio.file.Path

/** Each partition transition preserves the same lexical frontier and input revision. */
internal class IntellijDeclarationPartitionScanner(
    private val scope: CompiledIntellijSearchScope,
    private val state: IntellijDeclarationDiscoveryState,
    private val observePartition: (SemanticFilePartition) -> IntellijDeclarationPartitionObservation,
) {
    fun scan() {
        while (state.hasInput()) {
            if (!state.observe() || !state.resultFits() || !state.consume()) return
            if (!step()) return
        }
    }

    private fun step(): Boolean {
        val position = state.active as? SymbolDiscoveryActiveInput.Scanning
        val partition = position?.let { SemanticFilePartition.File(it.file) } ?: state.frontier.firstEntry().value
        val native = observe(partition)
        return when (native) {
            is IntellijDeclarationPartitionObservation.Rejected -> {
                state.qualifications += SymbolDiscoveryQualification.PROVIDER_FAILURE
                state.block = native.cause
                false
            }
            is IntellijDeclarationPartitionObservation.Directory -> directory(partition, position, native.children)
            IntellijDeclarationPartitionObservation.OutsideUniverse -> outside(partition, position)
            is IntellijDeclarationPartitionObservation.Source -> source(partition, position, native)
        }
    }

    private fun observe(partition: SemanticFilePartition): IntellijDeclarationPartitionObservation {
        val started = state.allowance.now()
        val directory = partition is SemanticFilePartition.Directory
        state.observation.phase(
            if (directory) IntellijReadPhase.DISCOVERY_INVENTORY else IntellijReadPhase.DECLARATION_SCAN
        )
        val native = observePartition(partition)
        if (directory) state.inventoryNanos += state.elapsed(started) else state.scanNanos += state.elapsed(started)
        return native
    }

    private fun directory(
        partition: SemanticFilePartition,
        position: SymbolDiscoveryActiveInput.Scanning?,
        children: List<SemanticFilePartition>,
    ): Boolean {
        if (position != null || partition !is SemanticFilePartition.Directory || !childrenAreValid(partition, children))
            return blocked(SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE)
        if (children.size > state.limits[ReadLimitParameter.DISCOVERY_FILES].value) {
            state.qualifications += SymbolDiscoveryQualification.WORK_LIMIT_REACHED
            return blocked(SymbolDiscoveryBlockCause.PARTITION_CAPACITY)
        }
        if (children.any { it.orderingPath in state.frontier })
            return blocked(SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE)
        val successorBytes = state.frontierBytes - partition.detachedBytes() + children.sumOf { it.detachedBytes() }
        if (!state.retentionFits(successorBytes)) return false
        state.frontier.pollFirstEntry()
        children.forEach { state.frontier[it.orderingPath] = it }
        state.frontierBytes = successorBytes
        state.inventoryFiles += children.count { it is SemanticFilePartition.File }
        return state.advance() && state.retentionFits()
    }

    private fun childrenAreValid(
        partition: SemanticFilePartition.Directory,
        children: List<SemanticFilePartition>,
    ): Boolean {
        val validPaths = children.none {
            it.orderingPath <= partition.orderingPath ||
                Path.of(it.orderingPath).parent != Path.of(partition.orderingPath)
        }
        return validPaths && children.map { it.orderingPath }.distinct().size == children.size
    }

    private fun outside(partition: SemanticFilePartition, position: SymbolDiscoveryActiveInput.Scanning?): Boolean {
        if (position != null) return blocked(SymbolDiscoveryBlockCause.PROVIDER_UNAVAILABLE)
        state.frontier.pollFirstEntry()
        state.frontierBytes -= partition.detachedBytes()
        if (partition is SemanticFilePartition.File) {
            state.discoveredFiles++
            state.completedFiles++
        }
        return state.advance()
    }

    private fun source(
        partition: SemanticFilePartition,
        position: SymbolDiscoveryActiveInput.Scanning?,
        native: IntellijDeclarationPartitionObservation.Source,
    ): Boolean {
        val file = native.file
        val identity = partition.location as? SymbolDiscoveryFileIdentity.Workspace
        if (partition !is SemanticFilePartition.File || identity == null)
            return blocked(SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE)
        state.reacquiredFiles++
        if (!file.isValid || !scope.nativeScope.contains(file.virtualFile))
            return blocked(SymbolDiscoveryBlockCause.PROVIDER_UNAVAILABLE)
        if (position == null) {
            state.frontier.pollFirstEntry()
            state.frontierBytes -= partition.detachedBytes()
            state.discoveredFiles++
            state.active =
                SymbolDiscoveryActiveInput.Scanning(identity, SymbolDiscoverySourceOffset.parse(0).discoveryRefined())
            if (!state.advance()) return false
        }
        when (
            state.request.constraints.packageName.admitPackage {
                IntellijPackageEvidence.Known(file.packageFqName.asString())
            }
        ) {
            IntellijDiscoveryItemAdmission.FILTERED -> state.finishFile()
            IntellijDiscoveryItemAdmission.UNSUPPORTED -> {
                state.qualifications += SymbolDiscoveryQualification.UNSUPPORTED_ITEM
                state.finishFile()
            }
            IntellijDiscoveryItemAdmission.ADMITTED -> {
                val started = state.allowance.now()
                IntellijDeclarationFileScanner(state).scan(file)
                state.scanNanos += state.elapsed(started)
            }
        }
        return state.active == SymbolDiscoveryActiveInput.Unopened
    }

    private fun blocked(cause: SymbolDiscoveryBlockCause): Boolean {
        state.block = cause
        return false
    }
}
