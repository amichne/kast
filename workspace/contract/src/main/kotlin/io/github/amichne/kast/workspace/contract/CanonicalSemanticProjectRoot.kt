package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

enum class CanonicalSemanticProjectRootFailure {
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
    OVERLAPS_WORKSPACE,
}

/**
 * Detached identity of the runtime-owned IntelliJ project whose imported model serves one Gradle
 * workspace.
 *
 * This identity is intentionally distinct from [CanonicalWorkspaceRoot]: the semantic project may
 * store generated IntelliJ configuration only at this root, while source and Gradle ownership stay
 * rooted at the workspace.
 */
class CanonicalSemanticProjectRoot private constructor(
    val workspaceRoot: CanonicalWorkspaceRoot,
    val value: String,
) {
    override fun equals(other: Any?): Boolean =
        other is CanonicalSemanticProjectRoot &&
            workspaceRoot == other.workspaceRoot && value == other.value

    override fun hashCode(): Int = 31 * workspaceRoot.hashCode() + value.hashCode()

    companion object {
        /**
         * Proof transition:
         * `(CanonicalWorkspaceRoot, Path) -> Refinement<CanonicalSemanticProjectRoot,
         * CanonicalSemanticProjectRootFailure>`.
         *
         * Establishes that a physically canonicalized semantic-project path is absolute,
         * lexically normalized, and disjoint from the exact Gradle workspace before retaining both
         * identities.
         */
        fun fromCanonicalPath(
            workspaceRoot: CanonicalWorkspaceRoot,
            path: Path,
        ): Refinement<CanonicalSemanticProjectRoot, CanonicalSemanticProjectRootFailure> {
            val workspacePath = Path.of(workspaceRoot.value)
            return when {
                !path.isAbsolute ->
                    Refinement.Rejected(CanonicalSemanticProjectRootFailure.NOT_ABSOLUTE)
                path.normalize() != path ->
                    Refinement.Rejected(CanonicalSemanticProjectRootFailure.NOT_NORMALIZED)
                path.startsWith(workspacePath) || workspacePath.startsWith(path) ->
                    Refinement.Rejected(CanonicalSemanticProjectRootFailure.OVERLAPS_WORKSPACE)
                else -> Refinement.Refined(
                    CanonicalSemanticProjectRoot(workspaceRoot, path.toString()),
                )
            }
        }
    }
}
