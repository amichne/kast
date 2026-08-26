package io.github.amichne.kast.runtime.ide.read.execution

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.runtime.ide.read.ExecutingProjectRead
import io.github.amichne.kast.runtime.ide.read.ProjectReadAdmission
import io.github.amichne.kast.runtime.ide.read.ProjectReadCancellationCause
import io.github.amichne.kast.runtime.ide.read.ProjectReadExecutionAdmission
import io.github.amichne.kast.runtime.ide.read.ProjectReadExecutionAdmissionFailure
import io.github.amichne.kast.runtime.ide.read.ProjectReadExecutionCancellation
import io.github.amichne.kast.runtime.ide.read.ProjectReadExecutionCancellationCause
import io.github.amichne.kast.runtime.ide.read.ProjectReadPermit
import io.github.amichne.kast.runtime.ide.read.ProjectReadPermitEnd
import io.github.amichne.kast.runtime.ide.read.ProjectReadPermitTerminal
import io.github.amichne.kast.runtime.ide.read.ProjectReadRetirement
import io.github.amichne.kast.runtime.ide.read.ProjectReadRetirementCause
import io.github.amichne.kast.runtime.ide.read.ProjectReadSingleFlight
import io.github.amichne.kast.runtime.ide.read.QueuedProjectReadObservation
import io.github.amichne.kast.runtime.ide.read.QueuedProjectReadRequest
import io.github.amichne.kast.runtime.ide.read.revalidation.DetachedIdeReadProjection
import io.github.amichne.kast.runtime.ide.read.revalidation.EpochRevalidationPhase
import io.github.amichne.kast.runtime.ide.read.revalidation.ProjectReadEpochObserver
import io.github.amichne.kast.runtime.ide.read.revalidation.RevalidatedIdeReadResult
import io.github.amichne.kast.runtime.ide.read.revalidation.revalidateIdeRead
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProject
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionAdmission
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionFailure
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionResult

/**
 * Project-scoped executor joining same-source freshness, single-flight authority, and IDEA reads.
 *
 * The live Project and generic operation surface stay internal. Public construction revalidates
 * freshness against the retained admitted Project before issuing executor authority.
 */
class CancellableProjectReadExecutor private constructor(
    private val singleFlight: ProjectReadSingleFlight,
    private val readPort: CancellableProjectReadPort,
    private val processFactory: CancellableProjectReadProcessFactory,
    private val epochObserver: ProjectReadEpochObserver,
) {
    private val executionLock = Any()
    private var running: RunningProjectRead = RunningProjectRead.None

    /**
     * Proof transition: `VfsPassiveReadCapability -> ProjectReadAdmission`.
     *
     * Refines comparable exact-root freshness into one active permit, one queued request, or the
     * finite KVP-020 failure set. No live IntelliJ authority crosses this boundary.
     */
    internal fun admit(freshness: VfsPassiveReadCapability): ProjectReadAdmission =
        singleFlight.admit(freshness)

    /** Observes exact queued authority so PCE promotion remains retrievable after rethrow. */
    internal fun observeQueued(request: QueuedProjectReadRequest): QueuedProjectReadObservation =
        singleFlight.observeQueued(request)

    /**
     * Proof transition: `(ProjectReadPermit,
     * CancellableProjectReadOperation<DetachedIdeReadProjection<Value>>) ->
     * CancellableProjectReadResult<Value>`.
     *
     * Establishes exact permit ownership and an owned progress indicator before running one
     * background, fail-fast, write-priority cancellable read. Platform cancellation terminalizes
     * the authority and propagates unchanged. Project extraction occurs only inside the port.
     */
    internal fun <Value : Any> execute(
        permit: ProjectReadPermit,
        operation: CancellableProjectReadOperation<Value>,
    ): CancellableProjectReadResult<Value> = when (val attempt = begin(permit)) {
        is ExecutionAttempt.Rejected -> CancellableProjectReadResult.PermitRejected(
            attempt.failure,
        )
        is ExecutionAttempt.Admitted -> finishAttempt(attempt) {
            attempt.process.execute(readPort, operation)
        }
    }

    /**
     * Proof transition: `(ProjectReadPermit, CancellableProjectReadOperation<Value>) ->
     * CancellableProjectReadResult<RevalidatedIdeReadResult<Value>>`.
     *
     * Establishes an exact retained-source epoch immediately before semantic execution and again
     * after detached projection while the permit still owns the queue barrier. Only equality
     * admits [RevalidatedIdeReadResult.Complete]; movement, incomparability, and observation
     * rejection remain finite typed data. Platform cancellation propagates unchanged.
     */
    internal fun <Value : Any> executeRevalidated(
        permit: ProjectReadPermit,
        operation: CancellableProjectReadOperation<DetachedIdeReadProjection<Value>>,
    ): CancellableProjectReadResult<RevalidatedIdeReadResult<Value>> = when (
        val attempt = begin(permit)
    ) {
        is ExecutionAttempt.Rejected -> CancellableProjectReadResult.PermitRejected(
            attempt.failure,
        )
        is ExecutionAttempt.Admitted -> finishAttempt(attempt) {
            when (val before = epochObserver.observe()) {
                is ProjectReadEpochObservation.Rejected ->
                    AdmittedProjectReadExecutionResult.Completed(
                        RevalidatedIdeReadResult.Rejected.EpochObservationRejected(
                            EpochRevalidationPhase.BEFORE,
                            before.failure,
                        ),
                    )
                is ProjectReadEpochObservation.Observed -> when (
                    val read = attempt.process.execute(readPort, operation)
                ) {
                    is AdmittedProjectReadExecutionResult.Completed -> {
                        val after = epochObserver.observe()
                        AdmittedProjectReadExecutionResult.Completed(
                            revalidateIdeRead(before.epoch, read.value, after),
                        )
                    }
                    is AdmittedProjectReadExecutionResult.Rejected -> read
                }
            }
        }
    }

    /**
     * Proof transition: `(ProjectReadPermit, ProjectReadCancellationCause) ->
     * ProjectReadExecutionCancellation`.
     *
     * Active authority ends immediately. Executing authority records the client cause and cancels
     * its indicator while retaining the queue barrier until the computation unwinds.
     */
    internal fun cancel(
        permit: ProjectReadPermit,
        cause: ProjectReadCancellationCause,
    ): ProjectReadExecutionCancellation = singleFlight.requestExecutionCancellation(
        permit,
        cause,
    ).also { cancellation ->
        when (cancellation) {
            is ProjectReadExecutionCancellation.Deferred -> cancelRunning(permit)
            is ProjectReadExecutionCancellation.AlreadyDeferred ->
                if (cancellation.terminal is ProjectReadPermitTerminal.Cancelled) {
                    cancelRunning(permit)
                }
            is ProjectReadExecutionCancellation.Ended,
            is ProjectReadExecutionCancellation.AlreadyTerminal,
            ProjectReadExecutionCancellation.NotOwned,
            -> Unit
        }
    }

    /**
     * Proof transition: `ProjectReadRetirementCause -> ProjectReadRetirement`.
     *
     * Retires retained authority and cancels its in-flight indicator. A deferred client cause is
     * preserved alongside the winning retirement cause in the resulting permit terminal.
     */
    internal fun retire(cause: ProjectReadRetirementCause): ProjectReadRetirement =
        singleFlight.retire(cause).also { cancelRunning() }

    /** Atomically refines one permit and installs its indicator cancellation capability. */
    private fun begin(permit: ProjectReadPermit): ExecutionAttempt = synchronized(executionLock) {
        val process = processFactory.prepare()
        when (val admission = singleFlight.beginExecution(permit)) {
            is ProjectReadExecutionAdmission.Rejected -> ExecutionAttempt.Rejected(
                admission.failure,
            )
            is ProjectReadExecutionAdmission.Admitted -> {
                check(running === RunningProjectRead.None) {
                    "single-flight admitted overlapping executor authority"
                }
                running = RunningProjectRead.Active(permit, admission.execution, process)
                ExecutionAttempt.Admitted(admission.execution, process)
            }
        }
    }

    private fun <Value : Any> finishAttempt(
        attempt: ExecutionAttempt.Admitted,
        observe: () -> AdmittedProjectReadExecutionResult<Value>,
    ): CancellableProjectReadResult<Value> {
        val observed = try {
            observe()
        } catch (cancelled: ProcessCanceledException) {
            clear(attempt.execution)
            singleFlight.cancelExecution(attempt.execution, cancelled.executionCause())
            throw cancelled
        } catch (defect: Throwable) {
            clear(attempt.execution)
            singleFlight.releaseExecution(attempt.execution)
            throw defect
        }
        clear(attempt.execution)
        return when (observed) {
            is AdmittedProjectReadExecutionResult.Completed -> complete(
                observed.value,
                singleFlight.releaseExecution(attempt.execution),
            )
            is AdmittedProjectReadExecutionResult.Rejected -> rejectObserved(
                attempt.execution,
                observed.failure,
            )
        }
    }

    /** Clears exact execution before a terminal transition can promote queued work. */
    private fun clear(execution: ExecutingProjectRead) = synchronized(executionLock) {
        val current = running as? RunningProjectRead.Active
        check(current?.execution === execution) { "executing indicator authority was lost" }
        running = RunningProjectRead.None
    }

    /** Cancels only a matching permit's live execution indicator. */
    private fun cancelRunning(permit: ProjectReadPermit) = synchronized(executionLock) {
        val current = running
        if (current is RunningProjectRead.Active && current.permit === permit) {
            current.process.cancel()
        }
    }

    /** Cancels the sole live execution during project-level retirement. */
    private fun cancelRunning() = synchronized(executionLock) {
        val current = running
        if (current is RunningProjectRead.Active) current.process.cancel()
    }

    /** Running indicator ownership retained only for exact executing authority. */
    private sealed interface RunningProjectRead {
        data object None : RunningProjectRead
        class Active(
            val permit: ProjectReadPermit,
            val execution: ExecutingProjectRead,
            val process: PreparedCancellableProjectRead,
        ) : RunningProjectRead
    }

    /** Closed executor-local refinement of a permit and platform cancellation capability. */
    private sealed interface ExecutionAttempt {
        data class Admitted(
            val execution: ExecutingProjectRead,
            val process: PreparedCancellableProjectRead,
        ) : ExecutionAttempt
        data class Rejected(val failure: ProjectReadExecutionAdmissionFailure) : ExecutionAttempt
    }

    /** Maps disposal separately so no other retirement cause can inhabit that result. */
    private fun rejectObserved(
        execution: ExecutingProjectRead,
        failure: AdmittedProjectReadExecutionFailure,
    ): CancellableProjectReadResult<Nothing> = when (failure) {
        AdmittedProjectReadExecutionFailure.PROJECT_DISPOSED -> when (
            val retirement = singleFlight.retire(ProjectReadRetirementCause.PROJECT_DISPOSED)
        ) {
            is ProjectReadRetirement.Retired -> disposeAfterRetirement(
                retirement,
                singleFlight.releaseExecution(execution),
            )
            is ProjectReadRetirement.AlreadyRetired -> when (
                val end = singleFlight.releaseExecution(execution)
            ) {
                is ProjectReadPermitEnd.AlreadyEnded ->
                    CancellableProjectReadResult.PermitInvalidated(
                        CancellableProjectReadInvalidation.HostAlreadyRetired(
                            retirement.cause,
                            end.terminal,
                        ),
                    )
                else -> invalidated(end)
            }
        }
        AdmittedProjectReadExecutionFailure.WRONG_THREAD -> reject(
            CancellableProjectReadHostRejection.WRONG_THREAD,
            singleFlight.releaseExecution(execution),
        )
        AdmittedProjectReadExecutionFailure.EXISTING_READ_ACCESS -> reject(
            CancellableProjectReadHostRejection.EXISTING_READ_ACCESS,
            singleFlight.releaseExecution(execution),
        )
        AdmittedProjectReadExecutionFailure.PROJECT_NOT_OPEN -> reject(
            CancellableProjectReadHostRejection.PROJECT_NOT_OPEN,
            singleFlight.releaseExecution(execution),
        )
        AdmittedProjectReadExecutionFailure.DUMB_MODE -> reject(
            CancellableProjectReadHostRejection.DUMB_MODE,
            singleFlight.releaseExecution(execution),
        )
    }

    companion object {
        /**
         * Proof transition: `(AdmittedIdeProject, VfsPassiveReadCapability) ->
         * CancellableProjectReadExecutorAdmission`.
         *
         * Reobserves the retained Project epoch source once. Only unchanged same-source freshness
         * creates an executor; every root or freshness failure remains closed typed data.
         */
        fun admit(
            project: AdmittedIdeProject,
            initialFreshness: VfsPassiveReadCapability,
        ): CancellableProjectReadExecutorAdmission = when (
            val admission = project.cancellableReadExecution(initialFreshness)
        ) {
            is AdmittedProjectReadExecutionAdmission.Admitted ->
                CancellableProjectReadExecutorAdmission.Admitted(
                    CancellableProjectReadExecutor(
                        ProjectReadSingleFlight.bind(admission.freshness),
                        LiveCancellableProjectReadPort(admission.execution),
                        LiveCancellableProjectReadProcessFactory,
                        ProjectReadEpochObserver(project::observeReadEpoch),
                    ),
                )
            is AdmittedProjectReadExecutionAdmission.Rejected ->
                CancellableProjectReadExecutorAdmission.Rejected(
                    when (val failure = admission.failure) {
                        AdmittedProjectReadExecutionAdmissionFailure.WrongProject ->
                            CancellableProjectReadExecutorAdmissionFailure.WrongProject
                        is AdmittedProjectReadExecutionAdmissionFailure.FreshnessRejected ->
                            CancellableProjectReadExecutorAdmissionFailure.FreshnessRejected(
                                failure.cause,
                            )
                    },
                )
        }
    }
}

/** Reports disposal only when its exact retirement terminal won over prior cancellation. */
private fun disposeAfterRetirement(
    retirement: ProjectReadRetirement.Retired,
    end: ProjectReadPermitEnd,
): CancellableProjectReadResult<Nothing> = when (end) {
    is ProjectReadPermitEnd.AlreadyEnded -> when (val terminal = end.terminal) {
        is ProjectReadPermitTerminal.Retired -> CancellableProjectReadResult.ProjectDisposed(
            retirement.authority,
        )
        is ProjectReadPermitTerminal.Cancelled -> CancellableProjectReadResult.PermitInvalidated(
            CancellableProjectReadInvalidation.CancellationPreservedAcrossRetirement(
                terminal.cause,
                retirement,
            ),
        )
        ProjectReadPermitTerminal.Released,
        is ProjectReadPermitTerminal.ExecutionCancelled,
        -> invalidated(end)
    }
    else -> invalidated(end)
}

/** Admits a computed value only when exact permit release succeeded. */
private fun <Value : Any> complete(
    value: Value,
    end: ProjectReadPermitEnd,
): CancellableProjectReadResult<Value> = when (end) {
    is ProjectReadPermitEnd.Ended -> when (end.terminal) {
        ProjectReadPermitTerminal.Released -> CancellableProjectReadResult.Completed(
            value,
            end.continuation,
        )
        is ProjectReadPermitTerminal.Cancelled,
        is ProjectReadPermitTerminal.ExecutionCancelled,
        is ProjectReadPermitTerminal.Retired,
        -> CancellableProjectReadResult.PermitInvalidated(
            CancellableProjectReadInvalidation.Terminalized(
                end.terminal,
                end.continuation,
            ),
        )
    }
    else -> invalidated(end)
}

/** Admits a host rejection only when exact permit release succeeded. */
private fun reject(
    failure: CancellableProjectReadHostRejection,
    end: ProjectReadPermitEnd,
): CancellableProjectReadResult<Nothing> = when (end) {
    is ProjectReadPermitEnd.Ended -> when (end.terminal) {
        ProjectReadPermitTerminal.Released -> CancellableProjectReadResult.HostRejected(
            failure,
            end.continuation,
        )
        is ProjectReadPermitTerminal.Cancelled,
        is ProjectReadPermitTerminal.ExecutionCancelled,
        is ProjectReadPermitTerminal.Retired,
        -> CancellableProjectReadResult.PermitInvalidated(
            CancellableProjectReadInvalidation.Terminalized(
                end.terminal,
                end.continuation,
            ),
        )
    }
    else -> invalidated(end)
}
