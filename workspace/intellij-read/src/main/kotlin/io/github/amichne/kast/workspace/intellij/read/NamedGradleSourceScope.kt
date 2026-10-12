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
    val evidence: IdeSourceRootEvidence,
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

    data class RootMapping(val cause: IdeRootMappingFailure) : NamedGradleSourceScopeFailure

    data class ModuleOwnership(val module: BoundedModuleName, val cause: GradleModuleOwnershipFailure) :
        NamedGradleSourceScopeFailure

    data object IDE_ROOT_INCOHERENT : NamedGradleSourceScopeFailure

    data object OWNER_UNAVAILABLE : NamedGradleSourceScopeFailure

    data object CAPTURE_LIMIT : NamedGradleSourceScopeFailure

    data object PROJECT_UNAVAILABLE : NamedGradleSourceScopeFailure

    data object INDEXING : NamedGradleSourceScopeFailure

    data class ModelRejected(val cause: WorkspaceSearchScopeModelCompilation.Rejected) : NamedGradleSourceScopeFailure

    data class ObservationFailed(val stage: NamedGradleCaptureStage) : NamedGradleSourceScopeFailure
}

enum class NamedGradleCaptureStage {
    PROJECT,
    CACHE,
    MODULES,
    SOURCE_SETS,
    OWNERSHIP,
}

/**
 * Exact Gradle ownership intersected with the existing IDE's code roots. Admission happens before name filtering, so
 * missing evidence cannot be promoted to a complete empty source-set match. File-index exclusions, directory/package
 * restrictions and live freshness remain read obligations.
 */
internal class NamedGradleSourceScope
private constructor(
    val model: WorkspaceSearchScopeModel,
    private val ownersByPath: Map<Path, List<ModelOwnedSourceRoot>>,
    private val ideRoots: Set<Path>,
    private val excludedRoots: Set<Path>,
) {
    fun contains(
        file: Path,
        sourceSets: SymbolDiscoverySourceSets,
        observation: IntellijReadObservation = IntellijReadObservation.None,
    ): Boolean {
        observation.count(IntellijReadCounter.NAMED_SOURCE_MEMBERSHIP_CHECKS)
        if (!file.isAbsolute || file.normalize() != file) return false
        // The first imported ancestor retains every tied owner at the deepest path. An exclusion
        // at that depth or below rejects; an exclusion above a deeper owner does not shadow it.
        for (ancestor in generateSequence(file) { it.parent }) {
            observation.count(IntellijReadCounter.NAMED_SOURCE_EXCLUSION_PROBES)
            if (ancestor in excludedRoots) return false
            observation.count(IntellijReadCounter.NAMED_SOURCE_OWNERSHIP_PROBES)
            val owners = ownersByPath[ancestor] ?: continue
            return ancestor in ideRoots &&
                owners.any { owner ->
                    owner.provenance == WorkspaceSourceRootProvenance.AUTHORED &&
                        when (sourceSets) {
                            SymbolDiscoverySourceSets.All -> true
                            is SymbolDiscoverySourceSets.Exact -> owner.sourceSet in sourceSets.values
                        }
                }
        }
        return false
    }

    companion object {
        fun admit(
            root: CanonicalWorkspaceRoot,
            observation: NamedGradleModelObservation,
            ideRoots: List<IdeCodeSourceRoot>,
            diagnostics: IntellijReadObservation = IntellijReadObservation.None,
        ): Refinement<NamedGradleSourceScope, NamedGradleSourceScopeFailure> {
            val captured =
                when (observation) {
                    NamedGradleModelObservation.Unavailable ->
                        return rejected(NamedGradleSourceScopeFailure.MODEL_UNAVAILABLE)
                    is NamedGradleModelObservation.Captured -> observation
                }
            val model =
                when (
                    val compiled =
                        WorkspaceSearchScopeModel.compile(
                            root,
                            ImportedWorkspaceModelState.COMPLETE,
                            captured.roots,
                        )
                ) {
                    is WorkspaceSearchScopeModelCompilation.Compiled -> compiled.model
                    is WorkspaceSearchScopeModelCompilation.Rejected ->
                        return rejected(NamedGradleSourceScopeFailure.ModelRejected(compiled))
                }
            val ownersByPath =
                model.sourceRoots.groupBy {
                    diagnostics.count(IntellijReadCounter.NAMED_SOURCE_ROOT_PATH_CONVERSIONS)
                    Path.of(it.sourceRoot.value)
                }
            when (val consistent = checkIdeRoots(ownersByPath, ideRoots)) {
                is Refinement.Rejected -> return consistent
                is Refinement.Refined -> Unit
            }
            if (
                captured.excludedRoots.any {
                    !it.isAbsolute || it.normalize() != it || !it.startsWith(Path.of(root.value))
                }
            ) {
                return rejected(NamedGradleSourceScopeFailure.IDE_ROOT_INCOHERENT)
            }
            return Refinement.Refined(
                NamedGradleSourceScope(
                    model,
                    ownersByPath,
                    ideRoots.map { it.path }.toSet(),
                    captured.excludedRoots.toSet(),
                )
            )
        }

        private fun checkIdeRoots(
            ownersByPath: Map<Path, List<ModelOwnedSourceRoot>>,
            ideRoots: List<IdeCodeSourceRoot>,
        ): Refinement<Unit, NamedGradleSourceScopeFailure> {
            for (ide in ideRoots) {
                val owners = ownersByPath[ide.path].orEmpty()
                if (owners.isEmpty())
                    return rejected(
                        NamedGradleSourceScopeFailure.RootMapping(
                            IdeRootMappingFailure.GradleOwnerMissing(ide.evidence)
                        )
                    )
                val mismatch = owners.firstOrNull { it.sourceKind != ide.kind || it.provenance != ide.provenance }
                if (mismatch != null) {
                    return rejected(
                        NamedGradleSourceScopeFailure.RootMapping(
                            IdeRootMappingFailure.RootClassificationMismatch(
                                ide.evidence,
                                CodeSourceRootClassification(ide.kind, ide.provenance),
                                CodeSourceRootClassification(mismatch.sourceKind, mismatch.provenance),
                            )
                        )
                    )
                }
            }
            return Refinement.Refined(Unit)
        }

        private fun rejected(failure: NamedGradleSourceScopeFailure) = Refinement.Rejected(failure)
    }
}
