package io.github.amichne.kast.runtime.ide.read.execution

import io.github.amichne.kast.runtime.ide.read.ProjectReadPermitEnd

/** Converts every non-successful permit end into closed result invalidation. */
internal fun invalidated(
    end: ProjectReadPermitEnd,
): CancellableProjectReadResult.PermitInvalidated = CancellableProjectReadResult.PermitInvalidated(
    when (end) {
        is ProjectReadPermitEnd.Ended -> CancellableProjectReadInvalidation.Terminalized(
            end.terminal,
            end.continuation,
        )
        is ProjectReadPermitEnd.AlreadyEnded -> CancellableProjectReadInvalidation.AlreadyEnded(
            end.terminal,
        )
        is ProjectReadPermitEnd.Deferred -> CancellableProjectReadInvalidation.Deferred(
            end.terminal,
        )
        ProjectReadPermitEnd.ExecutionInProgress ->
            CancellableProjectReadInvalidation.ExecutionInProgress
        ProjectReadPermitEnd.NotOwned -> CancellableProjectReadInvalidation.NotOwned
    },
)
