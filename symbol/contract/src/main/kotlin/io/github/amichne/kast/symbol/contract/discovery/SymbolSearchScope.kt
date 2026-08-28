package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleProjectIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import java.nio.file.Path

enum class SymbolSourceKindPolicy {
    PRODUCTION_ONLY,
    TEST_ONLY,
    PRODUCTION_AND_TEST,
}

enum class SymbolGeneratedSourcePolicy {
    EXCLUDE,
    INCLUDE,
}

enum class SymbolLibraryPolicy {
    EXCLUDE,
    INCLUDE,
}

enum class CanonicalWorkspaceFilePathFailure {
    INVALID_FILE_PATH,
    FILE_OUTSIDE_WORKSPACE,
}

@JvmInline
value class CanonicalWorkspaceFilePath private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition:
         * CanonicalWorkspaceRoot + Path to
         * Refinement<CanonicalWorkspaceFilePath, CanonicalWorkspaceFilePathFailure>.
         *
         * Establishes an absolute, normalized path strictly below the exact canonical workspace.
         * [CanonicalWorkspaceFilePathFailure] is the closed expected failure. A live file boundary
         * must prove existence and file kind; raw [Path] extraction is permitted only in the
         * request-local IntelliJ search-scope compiler.
         */
        fun fromCanonicalPath(
            workspaceRoot: CanonicalWorkspaceRoot,
            path: Path,
        ): Refinement<CanonicalWorkspaceFilePath, CanonicalWorkspaceFilePathFailure> {
            val root = Path.of(workspaceRoot.value)
            if (!path.isAbsolute || path.normalize() != path || path == root) {
                return Refinement.Rejected(CanonicalWorkspaceFilePathFailure.INVALID_FILE_PATH)
            }
            if (!path.startsWith(root)) {
                return Refinement.Rejected(CanonicalWorkspaceFilePathFailure.FILE_OUTSIDE_WORKSPACE)
            }
            return Refinement.Refined(CanonicalWorkspaceFilePath(path.toString()))
        }
    }
}

/**
 * Closed, detached symbol-scope policy. Target variants make exact-file, module, source-set,
 * Gradle-project, and workspace authority explicit. Production/test and generated-source policies
 * remain visible on every variant, while library readability can only be requested for the whole
 * IntelliJ workspace and never implies edit authority.
 */
sealed interface SymbolSearchScope {
    val sourceKinds: SymbolSourceKindPolicy
    val generatedSources: SymbolGeneratedSourcePolicy

    data class ExactFile(
        val file: CanonicalWorkspaceFilePath,
        override val sourceKinds: SymbolSourceKindPolicy,
        override val generatedSources: SymbolGeneratedSourcePolicy,
    ) : SymbolSearchScope

    data class Module(
        val module: WorkspaceModuleIdentity,
        override val sourceKinds: SymbolSourceKindPolicy,
        override val generatedSources: SymbolGeneratedSourcePolicy,
    ) : SymbolSearchScope

    data class SourceSet(
        val project: GradleProjectIdentity,
        val sourceSet: WorkspaceSourceSetName,
        override val sourceKinds: SymbolSourceKindPolicy,
        override val generatedSources: SymbolGeneratedSourcePolicy,
    ) : SymbolSearchScope

    data class GradleProject(
        val project: GradleProjectIdentity,
        override val sourceKinds: SymbolSourceKindPolicy,
        override val generatedSources: SymbolGeneratedSourcePolicy,
    ) : SymbolSearchScope

    data class Workspace(
        override val sourceKinds: SymbolSourceKindPolicy,
        override val generatedSources: SymbolGeneratedSourcePolicy,
        val libraries: SymbolLibraryPolicy,
    ) : SymbolSearchScope

    companion object {
        fun snapshot(scope: SymbolSearchScope): SymbolSearchScopeSnapshot = when (scope) {
            is ExactFile -> SymbolSearchScopeSnapshot(
                SymbolSearchScopeKind.EXACT_FILE,
                scope.file.value,
                null,
                scope.sourceKinds,
                scope.generatedSources,
                null,
            )
            is Module -> SymbolSearchScopeSnapshot(
                SymbolSearchScopeKind.MODULE,
                scope.module.value,
                null,
                scope.sourceKinds,
                scope.generatedSources,
                null,
            )
            is SourceSet -> SymbolSearchScopeSnapshot(
                SymbolSearchScopeKind.SOURCE_SET,
                scope.project.buildRoot.value,
                "${scope.project.projectPath.value}\u0000${scope.sourceSet.value}",
                scope.sourceKinds,
                scope.generatedSources,
                null,
            )
            is GradleProject -> SymbolSearchScopeSnapshot(
                SymbolSearchScopeKind.GRADLE_PROJECT,
                scope.project.buildRoot.value,
                scope.project.projectPath.value,
                scope.sourceKinds,
                scope.generatedSources,
                null,
            )
            is Workspace -> SymbolSearchScopeSnapshot(
                SymbolSearchScopeKind.WORKSPACE,
                null,
                null,
                scope.sourceKinds,
                scope.generatedSources,
                scope.libraries,
            )
        }

        fun restore(
            root: CanonicalWorkspaceRoot,
            sourceRoot: SourceRoot,
            captured: SymbolSearchScopeSnapshot,
        ): Refinement<SymbolSearchScope, SymbolSearchScopeRestorationFailure> {
            val scope = when (captured.kind) {
                SymbolSearchScopeKind.EXACT_FILE -> {
                    val value = captured.primary
                        ?: return Refinement.Rejected(SymbolSearchScopeRestorationFailure.MALFORMED)
                    val path = runCatching { Path.of(value) }.getOrNull()
                        ?: return Refinement.Rejected(
                            SymbolSearchScopeRestorationFailure.MALFORMED,
                        )
                    val file = when (val parsed = CanonicalWorkspaceFilePath.fromCanonicalPath(
                        root,
                        path,
                    )) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected -> return Refinement.Rejected(
                            SymbolSearchScopeRestorationFailure.MALFORMED,
                        )
                    }
                    ExactFile(file, captured.sourceKinds, captured.generatedSources)
                }
                SymbolSearchScopeKind.MODULE -> {
                    if (captured.primary != sourceRoot.owner.module.value) {
                        return Refinement.Rejected(SymbolSearchScopeRestorationFailure.MALFORMED)
                    }
                    Module(
                        sourceRoot.owner.module,
                        captured.sourceKinds,
                        captured.generatedSources,
                    )
                }
                SymbolSearchScopeKind.SOURCE_SET -> {
                    val parts = captured.secondary?.split('\u0000')
                        ?: return Refinement.Rejected(SymbolSearchScopeRestorationFailure.MALFORMED)
                    if (
                        captured.primary != sourceRoot.owner.project.buildRoot.value ||
                        parts.size != 2 ||
                        parts[0] != sourceRoot.owner.project.projectPath.value ||
                        parts[1] != sourceRoot.owner.sourceSet.value
                    ) {
                        return Refinement.Rejected(SymbolSearchScopeRestorationFailure.MALFORMED)
                    }
                    SourceSet(
                        sourceRoot.owner.project,
                        sourceRoot.owner.sourceSet,
                        captured.sourceKinds,
                        captured.generatedSources,
                    )
                }
                SymbolSearchScopeKind.GRADLE_PROJECT -> {
                    if (
                        captured.primary != sourceRoot.owner.project.buildRoot.value ||
                        captured.secondary != sourceRoot.owner.project.projectPath.value
                    ) {
                        return Refinement.Rejected(SymbolSearchScopeRestorationFailure.MALFORMED)
                    }
                    GradleProject(
                        sourceRoot.owner.project,
                        captured.sourceKinds,
                        captured.generatedSources,
                    )
                }
                SymbolSearchScopeKind.WORKSPACE -> Workspace(
                    captured.sourceKinds,
                    captured.generatedSources,
                    captured.libraries
                        ?: return Refinement.Rejected(SymbolSearchScopeRestorationFailure.MALFORMED),
                )
            }
            return if (snapshot(scope) == captured) {
                Refinement.Refined(scope)
            } else {
                Refinement.Rejected(SymbolSearchScopeRestorationFailure.MALFORMED)
            }
        }
    }
}

enum class SymbolSearchScopeKind {
    EXACT_FILE,
    MODULE,
    SOURCE_SET,
    GRADLE_PROJECT,
    WORKSPACE,
}

data class SymbolSearchScopeSnapshot(
    val kind: SymbolSearchScopeKind,
    val primary: String?,
    val secondary: String?,
    val sourceKinds: SymbolSourceKindPolicy,
    val generatedSources: SymbolGeneratedSourcePolicy,
    val libraries: SymbolLibraryPolicy?,
)

enum class SymbolSearchScopeRestorationFailure {
    MALFORMED,
}

/**
 * Detached operation policy for compiling one native symbol search scope. The lease binds the
 * request to one canonical workspace and published evidence generation; [scope] carries only
 * readable authority and cannot grant edit or mutation authority.
 */
data class SymbolSearchScopeRequest(
    val lease: SemanticReadLease,
    val scope: SymbolSearchScope,
)
