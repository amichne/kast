package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.workspace.contract.GradleProjectIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease

enum class SymbolReadableSources {
    AUTHORED_ONLY,
    AUTHORED_AND_GENERATED,
}

sealed interface SymbolSearchOwner {
    data object Workspace : SymbolSearchOwner

    data class GradleProject(
        val identity: GradleProjectIdentity,
    ) : SymbolSearchOwner
}

/**
 * Detached operation policy for compiling one native symbol search scope. The lease binds the
 * request to one canonical workspace and published evidence generation; readability carries no
 * edit or mutation authority.
 */
data class SymbolSearchScopeRequest(
    val lease: SemanticReadLease,
    val owner: SymbolSearchOwner,
    val readableSources: SymbolReadableSources,
)
