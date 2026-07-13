package io.github.amichne.kast.idea

internal enum class SecureWorkspaceRenamePhase {
    DETACH_TARGET,
    FINAL_COMMIT,
    RESTORE_TARGET,
    MOVE_CLEANUP,
    RESTORE_CLEANUP,
}
