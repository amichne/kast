package io.github.amichne.kast.runtime.ide.read.symbol

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.runtime.ide.read.ProjectReadAdmission
import io.github.amichne.kast.runtime.ide.read.ProjectReadCancellationCause
import io.github.amichne.kast.runtime.ide.read.ProjectReadContinuation
import io.github.amichne.kast.runtime.ide.read.ProjectReadExecutionAdmission
import io.github.amichne.kast.runtime.ide.read.ProjectReadExecutionCancellationCause
import io.github.amichne.kast.runtime.ide.read.ProjectReadPermit
import io.github.amichne.kast.runtime.ide.read.ProjectReadPermitEnd
import io.github.amichne.kast.runtime.ide.read.ProjectReadPermitTerminal
import io.github.amichne.kast.runtime.ide.read.ProjectReadSingleFlight
import io.github.amichne.kast.runtime.ide.read.QueuedProjectReadCancellation
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolDiscoverReadPort
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProject
import io.github.amichne.kast.runtime.ide.read.preparation.HostedProjectCurrentRead
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CancellationException

/** Closed failures before the retained Project can issue hosted discovery authority. */
internal enum class HostedSymbolDiscoveryPreparationFailure {
    NON_IDE_PROJECT_HOST,
    CURRENT_READ_UNAVAILABLE,
}

/** Closed result of preparing the exact-root hosted symbol discovery route. */
internal sealed interface HostedSymbolDiscoveryPreparation {
    data class Prepared(val discovery: HostedSymbolDiscovery) : HostedSymbolDiscoveryPreparation
    data class Rejected(val failure: HostedSymbolDiscoveryPreparationFailure) :
        HostedSymbolDiscoveryPreparation
}

/** Candidate boundary used to prove that isolated-runtime authority cannot enter this route. */
internal sealed interface HostedSymbolDiscoveryCandidate {
    data class ExistingProject(
        val project: HostedIdeReadProject,
        val nativeDiscovery: HostedNativeSymbolDiscovery,
    ) : HostedSymbolDiscoveryCandidate

    data object IsolatedRuntime : HostedSymbolDiscoveryCandidate
}

/**
 * Native discovery authority that can consume only a current VFS-passive read capability.
 *
 * The implementation is owned by hosted composition and delegates to the existing bounded
 * IntelliJ symbol adapter. The capability cannot recover import, refresh, process, persistence,
 * graph-build, or source-write authority.
 */
internal fun interface HostedNativeSymbolDiscovery {
    suspend fun execute(
        request: SymbolDiscoverRequest,
        currentRead: VfsPassiveReadCapability,
    ): OperationOutcome<
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection,
        >
}

/** One exact-root, single-flight, epoch-revalidated `symbol.discover` route. */
internal class HostedSymbolDiscovery private constructor(
    private val project: HostedIdeReadProject,
    private val nativeDiscovery: HostedNativeSymbolDiscovery,
    private val singleFlight: ProjectReadSingleFlight,
    private val executionGate: Mutex,
) : SymbolDiscoverReadPort {
    /**
     * Proof transition: `SymbolDiscoverRequest -> OperationOutcome<SymbolDiscoverResult,
     * SymbolDiscoverQualification, SymbolDiscoverRejection>`.
     *
     * Establishes current exact-root admission, one active execution authority, detached native
     * output within the request count, and same-source epoch equality after semantic work. Closed
     * protocol rejection preserves unavailable, busy, moved, or malformed native output states.
     * Raw Project and semantic values may exist only inside [nativeDiscovery].
     */
    override suspend fun execute(
        request: SymbolDiscoverRequest,
    ): OperationOutcome<
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection,
        > = executionGate.withLock { executeSerialized(request) }

    private suspend fun executeSerialized(
        request: SymbolDiscoverRequest,
    ): OperationOutcome<
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection,
        > {
        val before = when (val current = project.admitCurrentRead()) {
            is HostedProjectCurrentRead.Admitted -> current.capability
            is HostedProjectCurrentRead.EpochRejected,
            is HostedProjectCurrentRead.FreshnessRejected,
                -> return rejected(SymbolDiscoverRejection.WORKSPACE_NOT_READY)
        }
        return when (val admission = singleFlight.admit(before)) {
            is ProjectReadAdmission.Active -> executeActive(admission.permit, before, request)
            is ProjectReadAdmission.Queued -> when (singleFlight.cancelQueued(
                    admission.request,
                    ProjectReadCancellationCause.REQUEST_CANCELLED,
                )) {
                is QueuedProjectReadCancellation.Cancelled,
                is QueuedProjectReadCancellation.AlreadyTerminal,
                QueuedProjectReadCancellation.NotOwned,
                    -> rejected(SymbolDiscoverRejection.QUERY_REJECTED)
            }
            is ProjectReadAdmission.Rejected ->
                rejected(SymbolDiscoverRejection.WORKSPACE_NOT_READY)
        }
    }

    private suspend fun executeActive(
        permit: ProjectReadPermit,
        before: VfsPassiveReadCapability,
        request: SymbolDiscoverRequest,
    ): OperationOutcome<
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection,
        > {
        val execution = when (val admitted = singleFlight.beginExecution(permit)) {
            is ProjectReadExecutionAdmission.Admitted -> admitted.execution
            is ProjectReadExecutionAdmission.Rejected -> {
                return rejectAfterPermitRelease(permit)
            }
        }
        val observed = try {
            nativeDiscovery.execute(request, before)
        } catch (cancelled: ProcessCanceledException) {
            when (singleFlight.cancelExecution(
                execution,
                ProjectReadExecutionCancellationCause.PLATFORM_CANCELLED,
            )) {
                is ProjectReadPermitEnd.Ended,
                is ProjectReadPermitEnd.AlreadyEnded,
                is ProjectReadPermitEnd.Deferred,
                ProjectReadPermitEnd.ExecutionInProgress,
                ProjectReadPermitEnd.NotOwned,
                    -> throw cancelled
            }
        } catch (cancelled: CancellationException) {
            when (singleFlight.cancelExecution(
                execution,
                ProjectReadExecutionCancellationCause.PLATFORM_CANCELLED,
            )) {
                is ProjectReadPermitEnd.Ended,
                is ProjectReadPermitEnd.AlreadyEnded,
                is ProjectReadPermitEnd.Deferred,
                ProjectReadPermitEnd.ExecutionInProgress,
                ProjectReadPermitEnd.NotOwned,
                    -> throw cancelled
            }
        }
        val admitted = when (val after = project.admitCurrentRead()) {
            is HostedProjectCurrentRead.Admitted -> when (
                before.admittedEpoch.relationTo(after.capability.admittedEpoch)
            ) {
                ProjectReadEpochRelation.SAME -> observed.admitNativeOutcome(request)
                ProjectReadEpochRelation.MOVED,
                ProjectReadEpochRelation.INCOMPARABLE,
                    -> rejected(SymbolDiscoverRejection.WORKSPACE_NOT_READY)
            }
            is HostedProjectCurrentRead.EpochRejected,
            is HostedProjectCurrentRead.FreshnessRejected,
                -> rejected(SymbolDiscoverRejection.WORKSPACE_NOT_READY)
        }
        return when (val ended = singleFlight.releaseExecution(execution)) {
            is ProjectReadPermitEnd.Ended -> if (
                ended.terminal == ProjectReadPermitTerminal.Released &&
                ended.continuation == ProjectReadContinuation.Idle
            ) admitted else rejected(SymbolDiscoverRejection.QUERY_REJECTED)
            is ProjectReadPermitEnd.AlreadyEnded,
            is ProjectReadPermitEnd.Deferred,
            ProjectReadPermitEnd.ExecutionInProgress,
            ProjectReadPermitEnd.NotOwned,
                -> rejected(SymbolDiscoverRejection.QUERY_REJECTED)
        }
    }

    /**
     * Proof transition: `ProjectReadPermit -> rejected hosted discovery outcome`.
     *
     * Consumes every closed release state after execution admission fails, so no permit terminal
     * proof is discarded and no invalid authority can be reused.
     */
    private fun rejectAfterPermitRelease(
        permit: ProjectReadPermit,
    ): OperationOutcome<
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection,
        > = when (singleFlight.release(permit)) {
        is ProjectReadPermitEnd.Ended,
        is ProjectReadPermitEnd.AlreadyEnded,
        is ProjectReadPermitEnd.Deferred,
        ProjectReadPermitEnd.ExecutionInProgress,
        ProjectReadPermitEnd.NotOwned,
            -> rejected(SymbolDiscoverRejection.QUERY_REJECTED)
    }

    companion object {
        /**
         * Proof transition: `(HostedIdeReadProject, HostedNativeSymbolDiscovery) ->
         * HostedSymbolDiscoveryPreparation`.
         *
         * Establishes one exact-root controller from a just-admitted current read and the sole
         * nominal native discovery authority. Missing current state remains closed
         * [HostedSymbolDiscoveryPreparationFailure]. No Project lookup or fallback is available.
         */
        fun prepare(
            project: HostedIdeReadProject,
            nativeDiscovery: HostedNativeSymbolDiscovery,
        ): HostedSymbolDiscoveryPreparation = prepare(
            HostedSymbolDiscoveryCandidate.ExistingProject(project, nativeDiscovery),
        )

        @JvmSynthetic
        internal fun prepare(
            candidate: HostedSymbolDiscoveryCandidate,
        ): HostedSymbolDiscoveryPreparation = when (candidate) {
            HostedSymbolDiscoveryCandidate.IsolatedRuntime ->
                HostedSymbolDiscoveryPreparation.Rejected(
                    HostedSymbolDiscoveryPreparationFailure.NON_IDE_PROJECT_HOST,
                )
            is HostedSymbolDiscoveryCandidate.ExistingProject -> {
                val initial = when (val current = candidate.project.admitCurrentRead()) {
                    is HostedProjectCurrentRead.Admitted -> current.capability
                    is HostedProjectCurrentRead.EpochRejected,
                    is HostedProjectCurrentRead.FreshnessRejected,
                        -> return HostedSymbolDiscoveryPreparation.Rejected(
                            HostedSymbolDiscoveryPreparationFailure.CURRENT_READ_UNAVAILABLE,
                        )
                }
                HostedSymbolDiscoveryPreparation.Prepared(
                    HostedSymbolDiscovery(
                        candidate.project,
                        candidate.nativeDiscovery,
                        ProjectReadSingleFlight.bind(initial),
                        Mutex(),
                    ),
                )
            }
        }
    }
}

/**
 * Proof transition: native discovery outcome -> admitted hosted discovery outcome.
 *
 * Establishes the exact canonical operation and request result bound. Malformed native evidence
 * becomes the closed protocol query rejection; no primitive evidence is returned to the caller.
 */
private fun OperationOutcome<
    SymbolDiscoverResult,
    SymbolDiscoverQualification,
    SymbolDiscoverRejection,
    >.admitNativeOutcome(
    request: SymbolDiscoverRequest,
): OperationOutcome<
    SymbolDiscoverResult,
    SymbolDiscoverQualification,
    SymbolDiscoverRejection,
    > = when (this) {
    is OperationOutcome.Complete -> when (evidence.admitFor(request)) {
        NativeSymbolDiscoveryEvidenceAdmission.Admitted -> this
        NativeSymbolDiscoveryEvidenceAdmission.Rejected -> malformed()
    }
    is OperationOutcome.Qualified -> when (evidence.admitFor(request)) {
        NativeSymbolDiscoveryEvidenceAdmission.Admitted -> this
        NativeSymbolDiscoveryEvidenceAdmission.Rejected -> malformed()
    }
    is OperationOutcome.Rejected -> this
}

/** Closed result of validating one detached native evidence envelope. */
private sealed interface NativeSymbolDiscoveryEvidenceAdmission {
    data object Admitted : NativeSymbolDiscoveryEvidenceAdmission
    data object Rejected : NativeSymbolDiscoveryEvidenceAdmission
}

/**
 * Proof transition: `(EvidenceEnvelope<SymbolDiscoverResult>, SymbolDiscoverRequest) ->
 * NativeSymbolDiscoveryEvidenceAdmission`.
 *
 * Establishes exact operation identity and the public request result limit, or returns the sole
 * closed rejected state. Raw operation/count comparison is confined to this projection boundary.
 */
private fun EvidenceEnvelope<SymbolDiscoverResult>.admitFor(
    request: SymbolDiscoverRequest,
): NativeSymbolDiscoveryEvidenceAdmission = if (
    operation == CanonicalOperation.SYMBOL_DISCOVER.id &&
    payload.items.values.size <= request.limit.value
) {
    NativeSymbolDiscoveryEvidenceAdmission.Admitted
} else {
    NativeSymbolDiscoveryEvidenceAdmission.Rejected
}

private fun malformed(): OperationOutcome<
    SymbolDiscoverResult,
    SymbolDiscoverQualification,
    SymbolDiscoverRejection,
    > = rejected(SymbolDiscoverRejection.QUERY_REJECTED)

private fun rejected(reason: SymbolDiscoverRejection): OperationOutcome<
    SymbolDiscoverResult,
    SymbolDiscoverQualification,
    SymbolDiscoverRejection,
    > = OperationOutcome.Rejected(reason)
