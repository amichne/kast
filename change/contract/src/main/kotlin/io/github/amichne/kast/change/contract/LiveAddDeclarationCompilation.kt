package io.github.amichne.kast.change.contract

import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

enum class LiveAddDeclarationCompilationFailure {
    AUTHORITY_MOVED,
    PROJECT_UNAVAILABLE,
    TARGET_UNAVAILABLE,
    TARGET_READ_ONLY,
    DECLARATION_REJECTED,
    COMPILER_UNAVAILABLE,
}

sealed interface LiveAddDeclarationCompilation {
    data class Compiled(val intent: InstalledAddDeclarationIntent, val content: WorkspaceSourceContentHash) :
        LiveAddDeclarationCompilation

    data class Rejected(val failure: LiveAddDeclarationCompilationFailure) : LiveAddDeclarationCompilation
}
