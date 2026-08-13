package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleProjectIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease
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
