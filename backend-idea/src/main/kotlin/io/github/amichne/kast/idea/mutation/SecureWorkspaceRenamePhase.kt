package io.github.amichne.kast.idea.mutation

internal enum class SecureWorkspaceRenamePhase {
    DETACH_TARGET,
    FINAL_COMMIT,
    RESTORE_TARGET,
    MOVE_CLEANUP,
    RESTORE_CLEANUP,
}
