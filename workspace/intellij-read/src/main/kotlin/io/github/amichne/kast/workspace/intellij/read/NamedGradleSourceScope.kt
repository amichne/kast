package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.workspace.contract.*
import java.nio.file.Path

/** Raw observations supplied only by the imported Gradle and IDE root-model boundaries. */
internal data class IdeCodeSourceRoot(
    val path: Path,
    val kind: WorkspaceSourceRootKind,
    val provenance: WorkspaceSourceRootProvenance,
)

internal sealed interface NamedGradleModelObservation {
    data object Unavailable : NamedGradleModelObservation
    data class Captured(
        val roots: List<WorkspaceSourceRootBoundary>,
        val excludedRoots: Set<Path> = emptySet(),
    ) : NamedGradleModelObservation
}

sealed interface NamedGradleSourceScopeFailure {
    data object MODEL_UNAVAILABLE : NamedGradleSourceScopeFailure
    data object IDE_ROOT_UNMAPPED : NamedGradleSourceScopeFailure
    data object IDE_ROOT_INCOHERENT : NamedGradleSourceScopeFailure
    data object OWNER_UNAVAILABLE : NamedGradleSourceScopeFailure
    data object CAPTURE_LIMIT : NamedGradleSourceScopeFailure
    data object PROJECT_UNAVAILABLE : NamedGradleSourceScopeFailure
    data object INDEXING : NamedGradleSourceScopeFailure
    data class ModelRejected(val cause: WorkspaceSearchScopeModelCompilation.Rejected) : NamedGradleSourceScopeFailure
    data class ObservationFailed(val stage: NamedGradleCaptureStage) : NamedGradleSourceScopeFailure
}

enum class NamedGradleCaptureStage { PROJECT, CACHE, MODULES, SOURCE_SETS, OWNERSHIP }

/**
 * Exact Gradle ownership intersected with the existing IDE's code roots. Admission happens before
 * name filtering, so missing evidence cannot be promoted to a complete empty source-set match.
 * File-index exclusions, directory/package restrictions and live freshness remain read obligations.
 */
internal class NamedGradleSourceScope private constructor(
    val model: WorkspaceSearchScopeModel,
    private val ideRoots: Set<Path>,
    private val excludedRoots: Set<Path>,
) {
    fun contains(file: Path, sourceSets: SymbolDiscoverySourceSets): Boolean {
        if (!file.isAbsolute || file.normalize() != file) return false
        val owners = model.sourceRoots.filter { file.startsWith(Path.of(it.sourceRoot.value)) }
        val depth = owners.maxOfOrNull { Path.of(it.sourceRoot.value).nameCount } ?: return false
        if (excludedRoots.any { file.startsWith(it) && it.nameCount >= depth }) return false
        return owners.any { owner ->
            val path = Path.of(owner.sourceRoot.value)
            path.nameCount == depth && path in ideRoots &&
                owner.provenance == WorkspaceSourceRootProvenance.AUTHORED &&
                when (sourceSets) {
                    SymbolDiscoverySourceSets.All -> true
                    is SymbolDiscoverySourceSets.Exact -> owner.sourceSet in sourceSets.values
                }
        }
    }

    companion object {
        fun admit(
            root: CanonicalWorkspaceRoot,
            observation: NamedGradleModelObservation,
            ideRoots: List<IdeCodeSourceRoot>,
        ): Refinement<NamedGradleSourceScope, NamedGradleSourceScopeFailure> {
            val captured = when (observation) {
                NamedGradleModelObservation.Unavailable -> return rejected(NamedGradleSourceScopeFailure.MODEL_UNAVAILABLE)
                is NamedGradleModelObservation.Captured -> observation
            }
            val model = when (val compiled = WorkspaceSearchScopeModel.compile(
                root, ImportedWorkspaceModelState.COMPLETE, captured.roots,
            )) {
                is WorkspaceSearchScopeModelCompilation.Compiled -> compiled.model
                is WorkspaceSearchScopeModelCompilation.Rejected -> return rejected(NamedGradleSourceScopeFailure.ModelRejected(compiled))
            }
            for (ide in ideRoots) {
                val owners = model.sourceRoots.filter { Path.of(it.sourceRoot.value) == ide.path }
                if (owners.isEmpty()) return rejected(NamedGradleSourceScopeFailure.IDE_ROOT_UNMAPPED)
                if (owners.any { it.sourceKind != ide.kind || it.provenance != ide.provenance }) {
                    return rejected(NamedGradleSourceScopeFailure.IDE_ROOT_INCOHERENT)
                }
            }
            if (captured.excludedRoots.any { !it.isAbsolute || it.normalize() != it || !it.startsWith(Path.of(root.value)) }) {
                return rejected(NamedGradleSourceScopeFailure.IDE_ROOT_INCOHERENT)
            }
            return Refinement.Refined(NamedGradleSourceScope(model, ideRoots.map { it.path }.toSet(), captured.excludedRoots.toSet()))
        }

        private fun rejected(failure: NamedGradleSourceScopeFailure) = Refinement.Rejected(failure)
    }
}
