package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.nio.file.Path
import java.util.Collections

data class SemanticDependencySource(
    val root: ModelOwnedSourceRoot,
    val path: WorkspaceSourcePath,
    val content: WorkspaceSourceContentHash,
)

sealed interface SemanticInventoryFailure {
    data class UnknownModule(val module: WorkspaceModuleIdentity) : SemanticInventoryFailure

    data class SourceOwnership(val paths: Set<WorkspaceSourcePath>) : SemanticInventoryFailure

    data class DuplicateSources(val paths: Set<WorkspaceSourcePath>) : SemanticInventoryFailure

    data class DuplicateModules(val modules: Set<WorkspaceModuleIdentity>) : SemanticInventoryFailure

    data class ModuleCoverage(val missing: Set<WorkspaceModuleIdentity>, val unexpected: Set<WorkspaceModuleIdentity>) :
        SemanticInventoryFailure

    data object DependencyGraphMismatch : SemanticInventoryFailure
}

/** Terminal source enumeration for one module, including explicit empty completion. No semantic success is implied. */
class CompleteSemanticModuleSources
private constructor(
    internal val graph: SemanticModuleDependencies,
    val module: WorkspaceModuleIdentity,
    files: List<SemanticDependencySource>,
) {
    val files: List<SemanticDependencySource> = Collections.unmodifiableList(files.sortedBy { it.path })

    /** Preserve the issuing graph and exact module before a read-scoped owner retains this completion. */
    fun admitOwner(
        expectedGraph: SemanticModuleDependencies,
        expectedModule: WorkspaceModuleIdentity,
    ): Refinement<CompleteSemanticModuleSources, SemanticInventoryFailure> =
        when {
            graph !== expectedGraph -> Refinement.Rejected(SemanticInventoryFailure.DependencyGraphMismatch)
            module != expectedModule ->
                Refinement.Rejected(SemanticInventoryFailure.ModuleCoverage(setOf(expectedModule), setOf(module)))
            else -> Refinement.Refined(this)
        }

    companion object {
        /** Called after bounded native enumeration exhausts all admitted source roots for this module. */
        fun fromCompiler(
            graph: SemanticModuleDependencies,
            module: WorkspaceModuleIdentity,
            files: List<SemanticDependencySource>,
        ): Refinement<CompleteSemanticModuleSources, SemanticInventoryFailure> {
            if (module !in graph.modules) return Refinement.Rejected(SemanticInventoryFailure.UnknownModule(module))
            val roots = graph.model.sourceRoots.filter { it.module == module }
            val foreign =
                files
                    .filter { file ->
                        file.root !in roots ||
                            !Path.of(graph.model.workspaceRoot.value)
                                .resolve(file.path.value)
                                .startsWith(Path.of(file.root.sourceRoot.value))
                    }
                    .mapTo(linkedSetOf()) { it.path }
            if (foreign.isNotEmpty()) return Refinement.Rejected(SemanticInventoryFailure.SourceOwnership(foreign))
            val duplicates = files.groupBy { it.path }.filterValues { it.size != 1 }.keys
            if (duplicates.isNotEmpty())
                return Refinement.Rejected(SemanticInventoryFailure.DuplicateSources(duplicates))
            return Refinement.Refined(CompleteSemanticModuleSources(graph, module, files))
        }
    }
}

sealed interface SemanticInventoryComparison {
    data object Unchanged : SemanticInventoryComparison

    data object DomainChanged : SemanticInventoryComparison

    data class Changed(
        val added: Set<WorkspaceSourcePath>,
        val removed: Set<WorkspaceSourcePath>,
        val modified: Set<WorkspaceSourcePath>,
    ) : SemanticInventoryComparison
}

/** Complete source inventory of the exact dependency closure; absent modules cannot masquerade as empty modules. */
class SemanticDependencyInventory
private constructor(
    val closure: SemanticDependencyClosure,
    files: List<SemanticDependencySource>,
) {
    val files: List<SemanticDependencySource> = Collections.unmodifiableList(files.sortedBy { it.path })
    private val byPath = this.files.associateBy { it.path }

    fun compare(current: SemanticDependencyInventory): SemanticInventoryComparison {
        if (!closure.sameDomain(current.closure)) return SemanticInventoryComparison.DomainChanged
        val added = current.byPath.keys - byPath.keys
        val removed = byPath.keys - current.byPath.keys
        val modified =
            (byPath.keys intersect current.byPath.keys).filterTo(linkedSetOf()) {
                byPath.getValue(it) != current.byPath.getValue(it)
            }
        return if (added.isEmpty() && removed.isEmpty() && modified.isEmpty()) SemanticInventoryComparison.Unchanged
        else SemanticInventoryComparison.Changed(added, removed, modified)
    }

    companion object {
        fun admit(
            closure: SemanticDependencyClosure,
            completed: List<CompleteSemanticModuleSources>,
        ): Refinement<SemanticDependencyInventory, SemanticInventoryFailure> {
            val groups = completed.groupBy { it.module }
            val duplicates = groups.filterValues { it.size != 1 }.keys
            if (duplicates.isNotEmpty())
                return Refinement.Rejected(SemanticInventoryFailure.DuplicateModules(duplicates))
            val missing = closure.modules - groups.keys
            val unexpected = groups.keys - closure.modules
            if (missing.isNotEmpty() || unexpected.isNotEmpty())
                return Refinement.Rejected(SemanticInventoryFailure.ModuleCoverage(missing, unexpected))
            if (completed.any { it.graph !== closure.graph })
                return Refinement.Rejected(SemanticInventoryFailure.DependencyGraphMismatch)
            val files = completed.flatMap { it.files }
            val duplicateFiles = files.groupBy { it.path }.filterValues { it.size != 1 }.keys
            if (duplicateFiles.isNotEmpty())
                return Refinement.Rejected(SemanticInventoryFailure.DuplicateSources(duplicateFiles))
            return Refinement.Refined(SemanticDependencyInventory(closure, files))
        }
    }
}
