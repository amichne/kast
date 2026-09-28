package io.github.amichne.kast.change.contract

import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

enum class LiveReplaceBodyCompilationFailure {
    AUTHORITY_MOVED,
    PROJECT_UNAVAILABLE,
    TARGET_UNAVAILABLE,
    TARGET_READ_ONLY,
    UNSUPPORTED_TARGET,
    BODY_REJECTED,
    PREIMAGE_CHANGED,
    COMPILER_UNAVAILABLE,
}

sealed interface LiveReplaceBodyCompilation {
    data class Compiled(val intent: InstalledReplaceBodyIntent, val content: WorkspaceSourceContentHash) :
        LiveReplaceBodyCompilation

    data class Rejected(val failure: LiveReplaceBodyCompilationFailure) : LiveReplaceBodyCompilation
}
