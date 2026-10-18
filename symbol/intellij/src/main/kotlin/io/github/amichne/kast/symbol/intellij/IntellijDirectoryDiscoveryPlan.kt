package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path

/** A request-local directory choice precedes native supply. Only admitted named requests get this plan. */
internal class IntellijDirectoryDiscoveryPlan
private constructor(
    val scope: CompiledIntellijSearchScope,
    val request: SymbolDiscoveryRequest,
    val target: SymbolDiscoveryTarget.Name,
    val directory: Path,
    val traversal: SymbolDiscoveryContainment,
) {
    /** Pure intersection excludes siblings and descendant roots from a direct query. */
    fun intersection(root: Path): Path? =
        when (traversal) {
            SymbolDiscoveryContainment.DIRECT -> if (directory.startsWith(root)) directory else null
            SymbolDiscoveryContainment.DESCENDANTS ->
                when {
                    directory.startsWith(root) -> directory
                    root.startsWith(directory) -> root
                    else -> null
                }
        }

    fun admitsFile(path: Path): Boolean =
        when (traversal) {
            SymbolDiscoveryContainment.DIRECT -> path.parent == directory
            SymbolDiscoveryContainment.DESCENDANTS -> path.startsWith(directory)
        }

    class Prepared
    private constructor(
        val selection: IntellijDirectoryDiscoveryPlan,
        val sourceRoots: Set<Path>,
        val ownershipRoots: Set<Path>,
        val chargedBytes: Long,
    ) {
        companion object {
            fun prepare(
                selection: IntellijDirectoryDiscoveryPlan,
                consume: () -> Boolean,
                observation: IntellijReadObservation,
                limits: ReadLimits,
                retainedBytes: Long = 0L,
            ): Refinement<Prepared, SymbolDiscoveryBlockCause> {
                if (retainedBytes > limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value - PLAN_BINDING_BYTES)
                    return Refinement.Rejected(SymbolDiscoveryBlockCause.RETENTION_LIMIT)
                observation.count(IntellijReadCounter.DISCOVERY_BYTES_CHARGED, amount = PLAN_BINDING_BYTES.toInt())
                val preparation = DirectoryPlanPreparation(consume, observation, limits, retainedBytes)
                val selected =
                    when (val result = preparation.collect(selection.scope.sourceRoots)) {
                        is Refinement.Refined -> result.value
                        is Refinement.Rejected -> return result
                    }
                val owners =
                    when (val result = preparation.collect(selection.scope.ownershipRoots)) {
                        is Refinement.Refined -> result.value
                        is Refinement.Rejected -> return result
                    }
                return Refinement.Refined(Prepared(selection, selected, owners, preparation.bytes - retainedBytes))
            }
        }
    }

    companion object {
        fun select(
            scope: CompiledIntellijSearchScope,
            request: SymbolDiscoveryRequest,
        ): IntellijDirectoryDiscoveryRoute {
            val target = request.target
            val restriction = request.constraints.directory
            if (target !is SymbolDiscoveryTarget.Name || target.kind == SymbolNameDiscoveryKind.FILE)
                return IntellijDirectoryDiscoveryRoute.Existing
            return if (scope.scope.libraryPolicy() == SymbolLibraryPolicy.EXCLUDE && restriction != null) {
                if (scope.lease != request.scope.lease || scope.scope != request.scope.scope)
                    return IntellijDirectoryDiscoveryRoute.Rejected
                IntellijDirectoryDiscoveryRoute.Selected(
                    IntellijDirectoryDiscoveryPlan(
                        scope,
                        request,
                        target,
                        Path.of(scope.lease.workspaceRoot.value).resolve(restriction.directory.value).normalize(),
                        restriction.containment,
                    )
                )
            } else IntellijDirectoryDiscoveryRoute.Existing
        }
    }
}

internal sealed interface IntellijDirectoryDiscoveryRoute {
    data class Selected(val plan: IntellijDirectoryDiscoveryPlan) : IntellijDirectoryDiscoveryRoute

    data object Rejected : IntellijDirectoryDiscoveryRoute

    data object Existing : IntellijDirectoryDiscoveryRoute
}

private class DirectoryPlanPreparation(
    private val consume: () -> Boolean,
    private val observation: IntellijReadObservation,
    private val limits: ReadLimits,
    retainedBytes: Long,
) {
    var bytes = PLAN_BINDING_BYTES + retainedBytes
        private set

    fun collect(roots: List<ModelOwnedSourceRoot>): Refinement<Set<Path>, SymbolDiscoveryBlockCause> {
        val paths = linkedSetOf<Path>()
        for (root in roots) {
            if (!consume()) return Refinement.Rejected(SymbolDiscoveryBlockCause.INSUFFICIENT_EXECUTION_GRANT)
            observation.count(IntellijReadCounter.DISCOVERY_PLAN_ROOT_ENTRIES)
            observation.count(IntellijReadCounter.DISCOVERY_ROOT_PATH_CONVERSIONS)
            val path = Path.of(root.sourceRoot.value)
            if (path in paths) continue
            val added = ROOT_ENTRY_BYTES + 2L * root.sourceRoot.value.length
            if (added > limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value - bytes)
                return Refinement.Rejected(SymbolDiscoveryBlockCause.RETENTION_LIMIT)
            paths.add(path)
            bytes += added
            observation.count(IntellijReadCounter.DISCOVERY_BYTES_CHARGED, amount = added.toInt())
        }
        return Refinement.Refined(paths.toSet())
    }
}

private const val PLAN_BINDING_BYTES = 768L
private const val ROOT_ENTRY_BYTES = 96L
