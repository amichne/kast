package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

enum class CanonicalWorkspaceRootFailure {
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
}

/**
 * Detached identity of the exact canonical workspace root admitted by a physical workspace
 * adapter.
 */
@JvmInline
value class CanonicalWorkspaceRoot private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition:
         * `Path -> Refinement<CanonicalWorkspaceRoot, CanonicalWorkspaceRootFailure>`.
         *
         * Establishes that the already physically canonicalized adapter path is absolute and
         * lexically normalized before retaining it as detached text. [CanonicalWorkspaceRootFailure]
         * is the closed expected failure. Raw root text may be extracted only at a physical
         * workspace adapter boundary.
         */
        fun fromCanonicalPath(
            path: Path,
        ): Refinement<CanonicalWorkspaceRoot, CanonicalWorkspaceRootFailure> = when {
            !path.isAbsolute ->
                Refinement.Rejected(CanonicalWorkspaceRootFailure.NOT_ABSOLUTE)
            path.normalize() != path ->
                Refinement.Rejected(CanonicalWorkspaceRootFailure.NOT_NORMALIZED)
            else ->
                Refinement.Refined(CanonicalWorkspaceRoot(path.toString()))
        }
    }
}

/**
 * Detached proof that a semantic read was admitted for one exact canonical root and one published
 * evidence generation.
 */
data class SemanticReadLease(
    val workspaceRoot: CanonicalWorkspaceRoot,
    val generation: EvidenceGeneration,
)
