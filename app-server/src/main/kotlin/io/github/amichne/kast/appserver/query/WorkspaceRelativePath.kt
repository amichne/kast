package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement

/** Canonical forward-slash path relative to the query workspace; a single dot names its root. */
@JvmInline
internal value class WorkspaceRelativePath private constructor(val value: String) {
    companion object {
        /**
         * Refines String to an exact workspace-relative path, or [WorkspaceRelativePathFailure].
         * Preserves spelling and excludes traversal and absolute paths without filesystem effects.
         * Raw extraction belongs only to query serialization and canonical protocol lowering.
         */
        fun parse(raw: String): Refinement<WorkspaceRelativePath, WorkspaceRelativePathFailure> = when {
            raw.isBlank() -> Refinement.Rejected(WorkspaceRelativePathFailure.BLANK)
            raw.length > 1_048_576 -> Refinement.Rejected(WorkspaceRelativePathFailure.TOO_LONG)
            raw.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(raw) ->
                Refinement.Rejected(WorkspaceRelativePathFailure.ABSOLUTE)
            raw.any(Char::isISOControl) -> Refinement.Rejected(WorkspaceRelativePathFailure.CONTROL_CHARACTER)
            raw.contains('\\') || (raw != "." && raw.split('/').any { it.isBlank() || it == "." || it == ".." }) ->
                Refinement.Rejected(WorkspaceRelativePathFailure.NON_CANONICAL)
            else -> Refinement.Refined(WorkspaceRelativePath(raw))
        }
    }
}

internal enum class WorkspaceRelativePathFailure { BLANK, TOO_LONG, ABSOLUTE, CONTROL_CHARACTER, NON_CANONICAL }
