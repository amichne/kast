package io.github.amichne.kast.runtime.ide.read.symbol

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
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
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolResolveReadPort
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProject
import io.github.amichne.kast.runtime.ide.read.preparation.HostedProjectCurrentRead
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import java.util.concurrent.CancellationException

/** Closed failures before the retained Project can issue hosted exact-resolution authority. */
internal enum class HostedSymbolResolutionPreparationFailure {
    NON_IDE_PROJECT_HOST,
    CURRENT_READ_UNAVAILABLE,
}

/** Closed result of preparing the exact-root hosted `symbol.resolve` route. */
internal sealed interface HostedSymbolResolutionPreparation {
    data class Prepared(val resolution: HostedSymbolResolution) :
        HostedSymbolResolutionPreparation

    data class Rejected(val failure: HostedSymbolResolutionPreparationFailure) :
        HostedSymbolResolutionPreparation
}

/** Candidate boundary proving that an isolated runtime cannot enter exact resolution. */
internal sealed interface HostedSymbolResolutionCandidate {
    data class ExistingProject(
        val project: HostedIdeReadProject,
        val selectors: HostedCandidateSelectorAuthority,
    ) : HostedSymbolResolutionCandidate

    data object IsolatedRuntime : HostedSymbolResolutionCandidate
}

/** Closed result of refining parsed selector text into exact native resolution authority. */
internal sealed interface HostedCandidateSelectorAdmission {
    data class Admitted(val capability: HostedSymbolResolutionCapability) :
        HostedCandidateSelectorAdmission

    data class Rejected(val reason: SymbolResolveRejection) : HostedCandidateSelectorAdmission
}

/**
 * Candidate-selector authority for the retained exact-root Project.
 *
 * Implementations decode and admit only the opaque selector issued by hosted discovery. The
 * supplied read capability cannot recover import, refresh, process, persistence, graph-build, or
 * source-write authority.
 */
internal fun interface HostedCandidateSelectorAuthority {
    fun admit(
        request: SymbolResolveRequest,
        currentRead: VfsPassiveReadCapability,
    ): HostedCandidateSelectorAdmission
}

/** One admitted batch-owned candidate capability whose only effect is exact semantic resolution. */
internal fun interface HostedSymbolResolutionCapability {
    suspend fun resolve(): OperationOutcome<SymbolResolveResult, Nothing, SymbolResolveRejection>
}

/** One exact-root, single-flight, epoch-revalidated `symbol.resolve` route. */
internal class HostedSymbolResolution private constructor(
    private val project: HostedIdeReadProject,
    private val selectors: HostedCandidateSelectorAuthority,
    private val singleFlight: ProjectReadSingleFlight,
) : SymbolResolveReadPort {
    /**
     * Proof transition: `SymbolResolveRequest -> OperationOutcome<SymbolResolveResult, Nothing,
     * SymbolResolveRejection>`.
     *
     * Establishes current exact-root admission, a batch-owned candidate capability, one active
     * exact resolution, same-source epoch equality, and verified exact-operation output. Selector
     * admission and semantic failures remain closed [SymbolResolveRejection]. Raw selector text is
     * extractable only inside [selectors].
     */
    override suspend fun execute(
        request: SymbolResolveRequest,
    ): OperationOutcome<SymbolResolveResult, Nothing, SymbolResolveRejection> {
        val before = when (val current = project.admitCurrentRead()) {
            is HostedProjectCurrentRead.Admitted -> current.capability
            is HostedProjectCurrentRead.EpochRejected,
            is HostedProjectCurrentRead.FreshnessRejected,
                -> return rejected(SymbolResolveRejection.WORKSPACE_NOT_READY)
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
                    -> rejected(SymbolResolveRejection.WORKSPACE_NOT_READY)
            }
            is ProjectReadAdmission.Rejected ->
                rejected(SymbolResolveRejection.WORKSPACE_NOT_READY)
        }
    }

    private suspend fun executeActive(
        permit: ProjectReadPermit,
        before: VfsPassiveReadCapability,
        request: SymbolResolveRequest,
    ): OperationOutcome<SymbolResolveResult, Nothing, SymbolResolveRejection> {
        val execution = when (val admitted = singleFlight.beginExecution(permit)) {
            is ProjectReadExecutionAdmission.Admitted -> admitted.execution
            is ProjectReadExecutionAdmission.Rejected -> return rejectAfterPermitRelease(permit)
        }
        val observed = try {
            when (val selected = selectors.admit(request, before)) {
                is HostedCandidateSelectorAdmission.Admitted -> selected.capability.resolve()
                is HostedCandidateSelectorAdmission.Rejected -> rejected(selected.reason)
            }
        } catch (cancelled: ProcessCanceledException) {
            cancelExecution(execution)
            throw cancelled
        } catch (cancelled: CancellationException) {
            cancelExecution(execution)
            throw cancelled
        } catch (defect: RuntimeException) {
            cancelExecution(execution)
            throw defect
        }
        val admitted = when (val after = project.admitCurrentRead()) {
            is HostedProjectCurrentRead.Admitted -> when (
                before.admittedEpoch.relationTo(after.capability.admittedEpoch)
            ) {
                ProjectReadEpochRelation.SAME -> observed.admitNativeOutcome(request)
                ProjectReadEpochRelation.MOVED,
                ProjectReadEpochRelation.INCOMPARABLE,
                    -> rejected(SymbolResolveRejection.CANDIDATE_STALE)
            }
            is HostedProjectCurrentRead.EpochRejected,
            is HostedProjectCurrentRead.FreshnessRejected,
                -> rejected(SymbolResolveRejection.WORKSPACE_NOT_READY)
        }
        return when (val ended = singleFlight.releaseExecution(execution)) {
            is ProjectReadPermitEnd.Ended -> if (
                ended.terminal == ProjectReadPermitTerminal.Released &&
                ended.continuation == ProjectReadContinuation.Idle
            ) admitted else rejected(SymbolResolveRejection.WORKSPACE_NOT_READY)
            is ProjectReadPermitEnd.AlreadyEnded,
            is ProjectReadPermitEnd.Deferred,
            ProjectReadPermitEnd.ExecutionInProgress,
            ProjectReadPermitEnd.NotOwned,
                -> rejected(SymbolResolveRejection.WORKSPACE_NOT_READY)
        }
    }

    private fun cancelExecution(
        execution: io.github.amichne.kast.runtime.ide.read.ExecutingProjectRead,
    ) {
        when (singleFlight.cancelExecution(
            execution,
            ProjectReadExecutionCancellationCause.PLATFORM_CANCELLED,
        )) {
            is ProjectReadPermitEnd.Ended,
            is ProjectReadPermitEnd.AlreadyEnded,
            is ProjectReadPermitEnd.Deferred,
            ProjectReadPermitEnd.ExecutionInProgress,
            ProjectReadPermitEnd.NotOwned,
                -> Unit
        }
    }

    /** Consumes every closed release state after execution admission fails. */
    private fun rejectAfterPermitRelease(
        permit: ProjectReadPermit,
    ): OperationOutcome<SymbolResolveResult, Nothing, SymbolResolveRejection> =
        when (singleFlight.release(permit)) {
            is ProjectReadPermitEnd.Ended,
            is ProjectReadPermitEnd.AlreadyEnded,
            is ProjectReadPermitEnd.Deferred,
            ProjectReadPermitEnd.ExecutionInProgress,
            ProjectReadPermitEnd.NotOwned,
                -> rejected(SymbolResolveRejection.WORKSPACE_NOT_READY)
        }

    companion object {
        /**
         * Proof transition: `(HostedIdeReadProject, HostedCandidateSelectorAuthority) ->
         * HostedSymbolResolutionPreparation`.
         *
         * Establishes one exact-root controller and the sole candidate-selector authority after a
         * current read is admitted. Missing current state remains closed; Project lookup and
         * fallback are unavailable.
         */
        fun prepare(
            project: HostedIdeReadProject,
            selectors: HostedCandidateSelectorAuthority,
        ): HostedSymbolResolutionPreparation = prepare(
            HostedSymbolResolutionCandidate.ExistingProject(project, selectors),
        )

        @JvmSynthetic
        internal fun prepare(
            candidate: HostedSymbolResolutionCandidate,
        ): HostedSymbolResolutionPreparation = when (candidate) {
            HostedSymbolResolutionCandidate.IsolatedRuntime ->
                HostedSymbolResolutionPreparation.Rejected(
                    HostedSymbolResolutionPreparationFailure.NON_IDE_PROJECT_HOST,
                )
            is HostedSymbolResolutionCandidate.ExistingProject -> {
                val initial = when (val current = candidate.project.admitCurrentRead()) {
                    is HostedProjectCurrentRead.Admitted -> current.capability
                    is HostedProjectCurrentRead.EpochRejected,
                    is HostedProjectCurrentRead.FreshnessRejected,
                        -> return HostedSymbolResolutionPreparation.Rejected(
                            HostedSymbolResolutionPreparationFailure.CURRENT_READ_UNAVAILABLE,
                        )
                }
                HostedSymbolResolutionPreparation.Prepared(
                    HostedSymbolResolution(
                        candidate.project,
                        candidate.selectors,
                        ProjectReadSingleFlight.bind(initial),
                    ),
                )
            }
        }
    }
}

/**
 * Proof transition: native resolve outcome -> admitted hosted exact-selector output.
 *
 * Establishes exact operation identity and strict refinement beyond candidate text. Malformed or
 * qualified native evidence becomes closed candidate-stale rejection.
 */
private fun OperationOutcome<SymbolResolveResult, Nothing, SymbolResolveRejection>.
    admitNativeOutcome(
        request: SymbolResolveRequest,
    ): OperationOutcome<SymbolResolveResult, Nothing, SymbolResolveRejection> = when (this) {
    is OperationOutcome.Complete -> when (evidence.admitFor(request)) {
        NativeSymbolResolutionEvidenceAdmission.Admitted -> this
        NativeSymbolResolutionEvidenceAdmission.Rejected -> malformed()
    }
    is OperationOutcome.Qualified -> malformed()
    is OperationOutcome.Rejected -> this
}

private sealed interface NativeSymbolResolutionEvidenceAdmission {
    data object Admitted : NativeSymbolResolutionEvidenceAdmission
    data object Rejected : NativeSymbolResolutionEvidenceAdmission
}

/**
 * Proof transition: `(EvidenceEnvelope<SymbolResolveResult>, SymbolResolveRequest) ->
 * NativeSymbolResolutionEvidenceAdmission`.
 *
 * Establishes exact operation identity and that exact identity is not reconstructed by echoing the
 * weaker candidate token. Raw operation and selector comparison is confined to this boundary.
 */
private fun EvidenceEnvelope<SymbolResolveResult>.admitFor(
    request: SymbolResolveRequest,
): NativeSymbolResolutionEvidenceAdmission = if (
    operation == CanonicalOperation.SYMBOL_RESOLVE.id &&
    payload.exactSelector != request.candidateSelector
) NativeSymbolResolutionEvidenceAdmission.Admitted
else NativeSymbolResolutionEvidenceAdmission.Rejected

private fun malformed(): OperationOutcome<SymbolResolveResult, Nothing, SymbolResolveRejection> =
    rejected(SymbolResolveRejection.CANDIDATE_STALE)

private fun rejected(
    reason: SymbolResolveRejection,
): OperationOutcome<SymbolResolveResult, Nothing, SymbolResolveRejection> =
    OperationOutcome.Rejected(reason)
