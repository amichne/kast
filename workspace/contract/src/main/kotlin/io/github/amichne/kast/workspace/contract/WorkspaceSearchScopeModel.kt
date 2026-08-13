package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

enum class ImportedWorkspaceModelState {
    COMPLETE,
    INCOMPLETE,
}

enum class WorkspaceSourceRootProvenance {
    AUTHORED,
    GENERATED,
    UNKNOWN,
}

/**
 * Raw Gradle project-model source-root observation. Primitives are retained only at this
 * workspace-model boundary and are refined by [WorkspaceSearchScopeModel.compile].
 */
data class WorkspaceSourceRootBoundary(
    val ideaModuleName: String,
    val linkedBuildRoot: Path,
    val gradleProjectPath: String,
    val sourceSetName: String,
    val sourceRoot: Path,
    val provenance: WorkspaceSourceRootProvenance,
)

enum class WorkspaceSearchScopeModelFailure {
    MODEL_INCOMPLETE,
    INVALID_IDEA_MODULE_NAME,
    INVALID_LINKED_BUILD_ROOT,
    LINKED_BUILD_ROOT_OUTSIDE_WORKSPACE,
    INVALID_GRADLE_PROJECT_PATH,
    INVALID_SOURCE_SET_NAME,
    INVALID_SOURCE_ROOT,
    SOURCE_ROOT_OUTSIDE_WORKSPACE,
    UNKNOWN_SOURCE_ROOT_PROVENANCE,
    AMBIGUOUS_SOURCE_ROOT_OWNER,
    INCOHERENT_SOURCE_ROOT_PROVENANCE,
    NO_SOURCE_ROOTS,
}

@JvmInline
value class WorkspaceModuleIdentity internal constructor(
    val value: String,
)

@JvmInline
value class WorkspaceRelativeGradleBuildRoot internal constructor(
    val value: String,
)

@JvmInline
value class GradleProjectPath internal constructor(
    val value: String,
)

@JvmInline
value class WorkspaceSourceSetName internal constructor(
    val value: String,
)

@JvmInline
value class CanonicalSourceRoot internal constructor(
    val value: String,
)

@ConsistentCopyVisibility
data class GradleProjectIdentity internal constructor(
    val buildRoot: WorkspaceRelativeGradleBuildRoot,
    val projectPath: GradleProjectPath,
)

@ConsistentCopyVisibility
data class ModelOwnedSourceRoot internal constructor(
    val module: WorkspaceModuleIdentity,
    val project: GradleProjectIdentity,
    val sourceSet: WorkspaceSourceSetName,
    val sourceRoot: CanonicalSourceRoot,
    val provenance: WorkspaceSourceRootProvenance,
)

sealed interface WorkspaceSearchScopeModelCompilation {
    data class Compiled(
        val model: WorkspaceSearchScopeModel,
    ) : WorkspaceSearchScopeModelCompilation

    @ConsistentCopyVisibility
    data class Rejected internal constructor(
        val failures: Set<WorkspaceSearchScopeModelFailure>,
    ) : WorkspaceSearchScopeModelCompilation
}

/**
 * Detached proof of complete, coherent Gradle project-model ownership for source roots below one
 * exact canonical workspace.
 */
class WorkspaceSearchScopeModel private constructor(
    val workspaceRoot: CanonicalWorkspaceRoot,
    sourceRoots: List<ModelOwnedSourceRoot>,
) {
    val sourceRoots: List<ModelOwnedSourceRoot> = sourceRoots.toList()

    companion object {
        /**
         * Proof transition:
         * CanonicalWorkspaceRoot + ImportedWorkspaceModelState + Iterable<WorkspaceSourceRootBoundary>
         * to WorkspaceSearchScopeModelCompilation.
         *
         * A compiled result establishes complete model admission, normalized workspace-contained
         * roots, strong build/project/source-set identities, known model provenance, and one
         * coherent Gradle project owner per exact source root. [WorkspaceSearchScopeModelFailure]
         * is the closed expected failure. Raw paths and names may be extracted only by the physical
         * project-model adapter that calls this boundary.
         */
        fun compile(
            workspaceRoot: CanonicalWorkspaceRoot,
            modelState: ImportedWorkspaceModelState,
            boundaries: Iterable<WorkspaceSourceRootBoundary>,
        ): WorkspaceSearchScopeModelCompilation {
            if (modelState == ImportedWorkspaceModelState.INCOMPLETE) {
                return WorkspaceSearchScopeModelCompilation.Rejected(
                    setOf(WorkspaceSearchScopeModelFailure.MODEL_INCOMPLETE),
                )
            }

            val failures = linkedSetOf<WorkspaceSearchScopeModelFailure>()
            val roots = boundaries.mapNotNull { boundary ->
                when (val refinement = refineBoundary(workspaceRoot, boundary)) {
                    is Refinement.Refined -> refinement.value
                    is Refinement.Rejected -> {
                        failures += refinement.failure
                        null
                    }
                }
            }
            roots.groupBy(ModelOwnedSourceRoot::sourceRoot).values.forEach { owners ->
                if (owners.map(ModelOwnedSourceRoot::project).distinct().size > 1) {
                    failures += WorkspaceSearchScopeModelFailure.AMBIGUOUS_SOURCE_ROOT_OWNER
                }
                if (owners.map(ModelOwnedSourceRoot::provenance).distinct().size > 1) {
                    failures += WorkspaceSearchScopeModelFailure.INCOHERENT_SOURCE_ROOT_PROVENANCE
                }
            }
            if (roots.isEmpty()) {
                failures += WorkspaceSearchScopeModelFailure.NO_SOURCE_ROOTS
            }
            if (failures.isNotEmpty()) {
                return WorkspaceSearchScopeModelCompilation.Rejected(failures)
            }

            val orderedRoots = roots.distinct().sortedWith(
                compareBy(
                    { it.sourceRoot.value },
                    { it.project.buildRoot.value },
                    { it.project.projectPath.value },
                    { it.sourceSet.value },
                    { it.module.value },
                    { it.provenance.name },
                ),
            )
            return WorkspaceSearchScopeModelCompilation.Compiled(
                WorkspaceSearchScopeModel(workspaceRoot, orderedRoots),
            )
        }

        /**
         * Proof transition:
         * CanonicalWorkspaceRoot + WorkspaceSourceRootBoundary
         * to Refinement<ModelOwnedSourceRoot, Set<WorkspaceSearchScopeModelFailure>>.
         *
         * Establishes strong model ownership for one source root without filesystem I/O. Raw
         * extraction remains confined to [compile].
         */
        private fun refineBoundary(
            workspaceRoot: CanonicalWorkspaceRoot,
            boundary: WorkspaceSourceRootBoundary,
        ): Refinement<ModelOwnedSourceRoot, Set<WorkspaceSearchScopeModelFailure>> {
            val failures = linkedSetOf<WorkspaceSearchScopeModelFailure>()
            val workspacePath = Path.of(workspaceRoot.value)
            val moduleName = boundary.ideaModuleName.trim()
            val projectPath = boundary.gradleProjectPath.trim()
            val sourceSetName = boundary.sourceSetName.trim()

            if (moduleName.isEmpty()) {
                failures += WorkspaceSearchScopeModelFailure.INVALID_IDEA_MODULE_NAME
            }
            if (!boundary.linkedBuildRoot.isAbsolute ||
                boundary.linkedBuildRoot.normalize() != boundary.linkedBuildRoot
            ) {
                failures += WorkspaceSearchScopeModelFailure.INVALID_LINKED_BUILD_ROOT
            } else if (!boundary.linkedBuildRoot.startsWith(workspacePath)) {
                failures += WorkspaceSearchScopeModelFailure.LINKED_BUILD_ROOT_OUTSIDE_WORKSPACE
            }
            if (!GRADLE_PROJECT_PATH.matches(projectPath)) {
                failures += WorkspaceSearchScopeModelFailure.INVALID_GRADLE_PROJECT_PATH
            }
            if (sourceSetName.isEmpty()) {
                failures += WorkspaceSearchScopeModelFailure.INVALID_SOURCE_SET_NAME
            }
            if (!boundary.sourceRoot.isAbsolute || boundary.sourceRoot.normalize() != boundary.sourceRoot) {
                failures += WorkspaceSearchScopeModelFailure.INVALID_SOURCE_ROOT
            } else if (!boundary.sourceRoot.startsWith(workspacePath)) {
                failures += WorkspaceSearchScopeModelFailure.SOURCE_ROOT_OUTSIDE_WORKSPACE
            }
            if (boundary.provenance == WorkspaceSourceRootProvenance.UNKNOWN) {
                failures += WorkspaceSearchScopeModelFailure.UNKNOWN_SOURCE_ROOT_PROVENANCE
            }
            if (failures.isNotEmpty()) {
                return Refinement.Rejected(failures)
            }

            val relativeBuildRoot = workspacePath
                .relativize(boundary.linkedBuildRoot)
                .joinToString("/") { it.toString() }
                .ifEmpty { "." }
            return Refinement.Refined(
                ModelOwnedSourceRoot(
                    module = WorkspaceModuleIdentity(moduleName),
                    project = GradleProjectIdentity(
                        buildRoot = WorkspaceRelativeGradleBuildRoot(relativeBuildRoot),
                        projectPath = GradleProjectPath(projectPath),
                    ),
                    sourceSet = WorkspaceSourceSetName(sourceSetName),
                    sourceRoot = CanonicalSourceRoot(boundary.sourceRoot.toString()),
                    provenance = boundary.provenance,
                ),
            )
        }

        private val GRADLE_PROJECT_PATH = Regex(
            pattern = ":(?:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*)?",
        )
    }
}
