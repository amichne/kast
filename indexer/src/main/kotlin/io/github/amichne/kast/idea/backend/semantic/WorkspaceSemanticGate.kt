package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.spi.SemanticReadExecution
import io.github.amichne.kast.workspace.spi.SemanticReadExecutor
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseFailure

internal class WorkspaceSemanticGate(
    private val executor: SemanticReadExecutor,
) {
    /**
     * Proof transition: `SemanticReadExecutor + suspend (SemanticReadLease -> T) -> T`.
     *
     * Extracts a payload only from a completed execution whose canonical root and publication
     * generation were revalidated. [SemanticReadLeaseFailure] is rendered as the legacy
     * [ConflictException] only at this backend boundary.
     */
    suspend fun <T> current(operation: suspend (SemanticReadLease) -> T): T =
        when (val execution = executor.current(operation)) {
            is SemanticReadExecution.Completed -> execution.payload
            is SemanticReadExecution.Rejected -> throw conflict(execution.failure)
        }

    private fun conflict(failure: SemanticReadLeaseFailure): ConflictException = ConflictException(
        message = when (failure) {
            is SemanticReadLeaseFailure.WorkspaceUnavailable ->
                "Semantic operation started while the workspace was not READY"
            is SemanticReadLeaseFailure.LeaseClosed,
            is SemanticReadLeaseFailure.PublishedGenerationMoved,
            is SemanticReadLeaseFailure.PublishedGenerationUnrepresentable,
            is SemanticReadLeaseFailure.WorkspaceRootMoved,
            is SemanticReadLeaseFailure.WorkspaceRootUnrepresentable,
                -> "Workspace moved during the semantic operation; retry against the next READY generation"
        },
        details = mapOf("workspaceState" to failure.toString()),
    )
}
