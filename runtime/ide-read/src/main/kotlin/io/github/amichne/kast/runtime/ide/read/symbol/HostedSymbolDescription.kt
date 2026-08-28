package io.github.amichne.kast.runtime.ide.read.symbol

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
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
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolDescribeReadPort
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProject
import io.github.amichne.kast.runtime.ide.read.preparation.HostedProjectCurrentRead
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import java.util.concurrent.CancellationException

/** Closed failures before the retained Project can issue hosted symbol-description authority. */
internal enum class HostedSymbolDescriptionPreparationFailure {
    NON_IDE_PROJECT_HOST,
    CURRENT_READ_UNAVAILABLE,
}

/** Closed result of preparing the exact-root hosted `symbol.describe` route. */
internal sealed interface HostedSymbolDescriptionPreparation {
    data class Prepared(val description: HostedSymbolDescription) :
        HostedSymbolDescriptionPreparation

    data class Rejected(val failure: HostedSymbolDescriptionPreparationFailure) :
        HostedSymbolDescriptionPreparation
}

/** Candidate boundary proving that an isolated runtime cannot enter exact description. */
internal sealed interface HostedSymbolDescriptionCandidate {
    data class ExistingProject(
        val project: HostedIdeReadProject,
        val selectors: HostedExactSelectorAuthority,
    ) : HostedSymbolDescriptionCandidate

    data object IsolatedRuntime : HostedSymbolDescriptionCandidate
}

/** Closed result of refining parsed exact-selector text into native description authority. */
internal sealed interface HostedExactSelectorAdmission {
    data class Admitted(val capability: HostedSymbolDescriptionCapability) :
        HostedExactSelectorAdmission

    data class Rejected(val reason: SymbolDescribeRejection) : HostedExactSelectorAdmission
}

/**
 * Exact-selector authority for the retained exact-root Project.
 *
 * Implementations decode and admit only the exact selector issued by hosted resolution. The
 * supplied read capability cannot recover discovery, import, refresh, process, persistence,
 * graph-build, or source-write authority.
 */
internal fun interface HostedExactSelectorAuthority {
    /**
     * Proof transition: `(SymbolDescribeRequest, VfsPassiveReadCapability) ->
     * HostedExactSelectorAdmission`.
     *
     * Establishes that parsed request text names one exact selector admitted for the current
     * retained-Project read, or returns a closed [SymbolDescribeRejection]. Raw selector extraction
     * is permitted only inside this native hosted-composition boundary.
     */
    fun admit(
        request: SymbolDescribeRequest,
        currentRead: VfsPassiveReadCapability,
    ): HostedExactSelectorAdmission
}

/** One admitted request-owned exact-selector capability whose only effect is detached description. */
internal fun interface HostedSymbolDescriptionCapability {
    suspend fun describe(): OperationOutcome<SymbolDescribeResult, Nothing, SymbolDescribeRejection>
}

/** One exact-root, single-flight, epoch-revalidated `symbol.describe` route. */
internal class HostedSymbolDescription private constructor(
    private val project: HostedIdeReadProject,
    private val selectors: HostedExactSelectorAuthority,
    private val singleFlight: ProjectReadSingleFlight,
) : SymbolDescribeReadPort {
    /**
     * Proof transition: `SymbolDescribeRequest -> OperationOutcome<SymbolDescribeResult, Nothing,
     * SymbolDescribeRejection>`.
     *
     * Establishes current exact-root admission, request-owned exact-selector authority, one active
     * semantic description, same-source epoch equality, and verified same-selector detached output.
     * Selector admission and semantic failures remain closed [SymbolDescribeRejection]. Raw selector
     * text is extractable only inside [selectors]. Cancellation and unexpected native defects
     * terminalize their execution authority before propagating to the platform boundary.
     */
    override suspend fun execute(
        request: SymbolDescribeRequest,
    ): OperationOutcome<SymbolDescribeResult, Nothing, SymbolDescribeRejection> {
        val before = when (val current = project.admitCurrentRead()) {
            is HostedProjectCurrentRead.Admitted -> current.capability
            is HostedProjectCurrentRead.EpochRejected,
            is HostedProjectCurrentRead.FreshnessRejected,
                -> return rejected(SymbolDescribeRejection.WORKSPACE_NOT_READY)
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
                    -> rejected(SymbolDescribeRejection.WORKSPACE_NOT_READY)
            }
            is ProjectReadAdmission.Rejected ->
                rejected(SymbolDescribeRejection.WORKSPACE_NOT_READY)
        }
    }

    private suspend fun executeActive(
        permit: ProjectReadPermit,
        before: VfsPassiveReadCapability,
        request: SymbolDescribeRequest,
    ): OperationOutcome<SymbolDescribeResult, Nothing, SymbolDescribeRejection> {
        val execution = when (val admitted = singleFlight.beginExecution(permit)) {
            is ProjectReadExecutionAdmission.Admitted -> admitted.execution
            is ProjectReadExecutionAdmission.Rejected -> return rejectAfterPermitRelease(permit)
        }
        val observed = try {
            when (val selected = selectors.admit(request, before)) {
                is HostedExactSelectorAdmission.Admitted -> selected.capability.describe()
                is HostedExactSelectorAdmission.Rejected -> rejected(selected.reason)
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
                    -> rejected(SymbolDescribeRejection.SELECTOR_STALE)
            }
            is HostedProjectCurrentRead.EpochRejected,
            is HostedProjectCurrentRead.FreshnessRejected,
                -> rejected(SymbolDescribeRejection.WORKSPACE_NOT_READY)
        }
        return when (val ended = singleFlight.releaseExecution(execution)) {
            is ProjectReadPermitEnd.Ended -> if (
                ended.terminal == ProjectReadPermitTerminal.Released &&
                ended.continuation == ProjectReadContinuation.Idle
            ) admitted else rejected(SymbolDescribeRejection.WORKSPACE_NOT_READY)
            is ProjectReadPermitEnd.AlreadyEnded,
            is ProjectReadPermitEnd.Deferred,
            ProjectReadPermitEnd.ExecutionInProgress,
            ProjectReadPermitEnd.NotOwned,
                -> rejected(SymbolDescribeRejection.WORKSPACE_NOT_READY)
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

    private fun rejectAfterPermitRelease(
        permit: ProjectReadPermit,
    ): OperationOutcome<SymbolDescribeResult, Nothing, SymbolDescribeRejection> =
        when (singleFlight.release(permit)) {
            is ProjectReadPermitEnd.Ended,
            is ProjectReadPermitEnd.AlreadyEnded,
            is ProjectReadPermitEnd.Deferred,
            ProjectReadPermitEnd.ExecutionInProgress,
            ProjectReadPermitEnd.NotOwned,
                -> rejected(SymbolDescribeRejection.WORKSPACE_NOT_READY)
        }

    companion object {
        /**
         * Proof transition: `(HostedIdeReadProject, HostedExactSelectorAuthority) ->
         * HostedSymbolDescriptionPreparation`.
         *
         * Establishes one exact-root controller and the sole exact-selector authority after a
         * current read is admitted. Missing current state remains closed; Project lookup,
         * rediscovery, and fallback are unavailable.
         */
        fun prepare(
            project: HostedIdeReadProject,
            selectors: HostedExactSelectorAuthority,
        ): HostedSymbolDescriptionPreparation = prepare(
            HostedSymbolDescriptionCandidate.ExistingProject(project, selectors),
        )

        @JvmSynthetic
        internal fun prepare(
            candidate: HostedSymbolDescriptionCandidate,
        ): HostedSymbolDescriptionPreparation = when (candidate) {
            HostedSymbolDescriptionCandidate.IsolatedRuntime ->
                HostedSymbolDescriptionPreparation.Rejected(
                    HostedSymbolDescriptionPreparationFailure.NON_IDE_PROJECT_HOST,
                )
            is HostedSymbolDescriptionCandidate.ExistingProject -> {
                val initial = when (val current = candidate.project.admitCurrentRead()) {
                    is HostedProjectCurrentRead.Admitted -> current.capability
                    is HostedProjectCurrentRead.EpochRejected,
                    is HostedProjectCurrentRead.FreshnessRejected,
                        -> return HostedSymbolDescriptionPreparation.Rejected(
                            HostedSymbolDescriptionPreparationFailure.CURRENT_READ_UNAVAILABLE,
                        )
                }
                HostedSymbolDescriptionPreparation.Prepared(
                    HostedSymbolDescription(
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
 * Proof transition: native describe outcome -> admitted hosted same-selector detached output.
 *
 * Establishes exact operation identity and exact preservation of the request selector. Malformed
 * or qualified native evidence becomes closed selector-stale rejection.
 */
private fun OperationOutcome<SymbolDescribeResult, Nothing, SymbolDescribeRejection>.
    admitNativeOutcome(
        request: SymbolDescribeRequest,
    ): OperationOutcome<SymbolDescribeResult, Nothing, SymbolDescribeRejection> = when (this) {
    is OperationOutcome.Complete -> when (evidence.admitFor(request)) {
        NativeSymbolDescriptionEvidenceAdmission.Admitted -> this
        NativeSymbolDescriptionEvidenceAdmission.Rejected -> malformed()
    }
    is OperationOutcome.Qualified -> malformed()
    is OperationOutcome.Rejected -> this
}

private sealed interface NativeSymbolDescriptionEvidenceAdmission {
    data object Admitted : NativeSymbolDescriptionEvidenceAdmission
    data object Rejected : NativeSymbolDescriptionEvidenceAdmission
}

/**
 * Proof transition: `(EvidenceEnvelope<SymbolDescribeResult>, SymbolDescribeRequest) ->
 * NativeSymbolDescriptionEvidenceAdmission`.
 *
 * Establishes exact operation identity and same-selector output. Raw comparison is confined to
 * this boundary; the detached document is the only accepted value that can leave native work.
 */
private fun EvidenceEnvelope<SymbolDescribeResult>.admitFor(
    request: SymbolDescribeRequest,
): NativeSymbolDescriptionEvidenceAdmission = if (
    operation == CanonicalOperation.SYMBOL_DESCRIBE.id &&
    payload.symbol.selector == request.exactSelector
) NativeSymbolDescriptionEvidenceAdmission.Admitted
else NativeSymbolDescriptionEvidenceAdmission.Rejected

private fun malformed(): OperationOutcome<SymbolDescribeResult, Nothing, SymbolDescribeRejection> =
    rejected(SymbolDescribeRejection.SELECTOR_STALE)

private fun rejected(
    reason: SymbolDescribeRejection,
): OperationOutcome<SymbolDescribeResult, Nothing, SymbolDescribeRejection> =
    OperationOutcome.Rejected(reason)
