package io.github.amichne.kast.source.contract

import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/** Exact publication identity supplied to one request-local native source read. */
data class SourceReadContext(
    val lease: SemanticReadLease,
    val sourceState: WorkspaceStateIdentity,
)

/** Narrow effect port; implementations may use IntelliJ/K2 only for the duration of this call. */
fun interface SourceReadPort {
    suspend fun read(
        context: SourceReadContext,
        request: SourceReadRequest,
    ): SourceReadResult
}
