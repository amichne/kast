package io.github.amichne.kast.source.contract

import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/** Content view retained with the exact request authority; IDE epochs are not publication state. */
sealed interface SourceReadContext {
    val lease: SemanticReadAuthority

    data class Published(
        override val lease: SemanticReadLease,
        val sourceState: WorkspaceStateIdentity,
    ) : SourceReadContext

    data class Live(override val lease: LiveSemanticReadAuthority) : SourceReadContext

    companion object {
        operator fun invoke(lease: SemanticReadLease, sourceState: WorkspaceStateIdentity): Published =
            Published(lease, sourceState)
    }
}

/** Narrow effect port; implementations may use IntelliJ/K2 only for the duration of this call. */
fun interface SourceReadPort {
    suspend fun read(context: SourceReadContext, request: SourceReadRequest): SourceReadResult
}

/** Original-owner admission supplies a current content context for one exact authority. */
fun interface SourceReadContextPort {
    suspend fun admit(
        expected: SemanticReadAuthority
    ): io.github.amichne.kast.kernel.Refinement<SourceReadContext, SourceReadRejection>
}
