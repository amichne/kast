package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.spi.SemanticReadExecution
import io.github.amichne.kast.workspace.spi.SemanticReadExecutor
import io.github.amichne.kast.workspace.spi.SemanticReadAdmissionFailure
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseFailure
import io.github.amichne.kast.workspace.spi.SemanticReadFreshnessRequirement
import io.github.amichne.kast.workspace.spi.RuntimeLivenessFailure

internal class WorkspaceSemanticGate(
    private val executor: SemanticReadExecutor,
) {
    /**
     * Proof transition: `SemanticReadExecutor + suspend (SemanticReadLease -> T) -> T`.
     *
     * Extracts a payload only from a completed execution whose canonical root and publication
     * generation were revalidated. [SemanticReadAdmissionFailure] is rendered as the legacy
     * [ConflictException] only at this backend boundary.
     */
    suspend fun <T> current(operation: suspend (SemanticReadLease) -> T): T =
        when (val execution = executor.current(operation = operation)) {
            is SemanticReadExecution.Completed -> execution.payload
            is SemanticReadExecution.Rejected -> throw conflict(execution.failure)
        }

    /**
     * Proof transition:
     * `SemanticReadExecutor + suspend (SemanticReadLease -> T) -> T`.
     *
     * Admits dumb-mode execution only for operations whose payload explicitly carries qualified
     * incomplete evidence. Runtime, transition, blocked-workspace, root, and generation failures
     * remain closed [SemanticReadAdmissionFailure] values rendered at this boundary.
     */
    suspend fun <T> currentWithQualifiedDumbModeEvidence(
        operation: suspend (SemanticReadLease) -> T,
    ): T = when (
        val execution = executor.current(
            freshness = SemanticReadFreshnessRequirement.QUALIFIED_DUMB_MODE,
            operation = operation,
        )
    ) {
        is SemanticReadExecution.Completed -> execution.payload
        is SemanticReadExecution.Rejected -> throw conflict(execution.failure)
    }

    private fun conflict(failure: SemanticReadAdmissionFailure): ConflictException = when (failure) {
        is SemanticReadAdmissionFailure.RuntimeUnavailable -> runtimeConflict(failure.failure)
        is SemanticReadAdmissionFailure.SemanticUnavailable -> semanticConflict(failure.failure)
    }

    private fun runtimeConflict(failure: RuntimeLivenessFailure): ConflictException = ConflictException(
        message = when (failure) {
            is RuntimeLivenessFailure.FrozenEventDispatchThread ->
                "IntelliJ event dispatch thread did not respond within ${failure.timeout.milliseconds} ms"
            RuntimeLivenessFailure.RuntimeDisposed -> "IntelliJ runtime project is disposed"
            RuntimeLivenessFailure.ProbeInterrupted -> "IntelliJ runtime liveness probe was interrupted"
            RuntimeLivenessFailure.ProbeUnavailable -> "IntelliJ runtime liveness probe is unavailable"
        },
        details = mapOf("runtimeLiveness" to failure.toString()),
    )

    private fun semanticConflict(failure: SemanticReadLeaseFailure): ConflictException = ConflictException(
        message = when (failure) {
            SemanticReadLeaseFailure.DumbMode ->
                "IntelliJ semantic indexes are unavailable during dumb mode"
            SemanticReadLeaseFailure.TransitionInProgress ->
                "Workspace transition is in progress"
            SemanticReadLeaseFailure.WorkspaceBlocked ->
                "Workspace reconciliation is blocked"
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
