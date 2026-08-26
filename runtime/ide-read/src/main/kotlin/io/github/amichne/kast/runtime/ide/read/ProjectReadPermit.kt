package io.github.amichne.kast.runtime.ide.read

import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability

/** Opaque authority whose construction, freshness, and lifecycle stay private to [Controller]. */
class ProjectReadPermit private constructor(
    private val owner: OwnerToken,
    private val freshness: VfsPassiveReadCapability,
) {
    private var lifecycle: PermitLifecycle = PermitLifecycle.Active

    private fun end(terminal: ProjectReadPermitTerminal) {
        check(lifecycle !is PermitLifecycle.Terminal)
        lifecycle = PermitLifecycle.Terminal(terminal)
    }

    private class OwnerToken

    private sealed interface PermitLifecycle {
        data object Active : PermitLifecycle
        class Executing(
            val authority: ExecutingProjectRead,
            val completion: ExecutionCompletion,
        ) : PermitLifecycle
        class Terminal(val value: ProjectReadPermitTerminal) : PermitLifecycle
    }

    private sealed interface ExecutionCompletion {
        data object NotRequested : ExecutionCompletion
        class CancellationRequested(val cause: ProjectReadCancellationCause) : ExecutionCompletion
    }

    private sealed interface ActivePermitEnd {
        data object Release : ActivePermitEnd
        class Cancel(val cause: ProjectReadCancellationCause) : ActivePermitEnd
    }

    internal class QueuedRequest private constructor(
        private val owner: OwnerToken,
        private val freshness: VfsPassiveReadCapability,
    ) {
        private var lifecycle: QueueLifecycle = QueueLifecycle.Pending

        private fun end(value: QueuedProjectReadTerminal) {
            check(lifecycle === QueueLifecycle.Pending)
            lifecycle = QueueLifecycle.Terminal(value)
        }

        private sealed interface QueueLifecycle {
            data object Pending : QueueLifecycle
            class Terminal(val value: QueuedProjectReadTerminal) : QueueLifecycle
        }

    /** Exact-root controller owning one active and one queued slot without raw Project or I/O. */
    internal class Controller private constructor(
        initialFreshness: VfsPassiveReadCapability,
    ) {
        private val lock = Any()
        private val owner = OwnerToken()
        private var state: State = State.Idle(ProjectReadScope.bind(initialFreshness))

        /** `VfsPassiveReadCapability -> ProjectReadAdmission`; admits exact scope or closed failure. */
        fun admit(freshness: VfsPassiveReadCapability): ProjectReadAdmission = synchronized(lock) {
            when (val current = state) {
                is State.Idle -> when (val scoped = current.scope.admit(freshness)) {
                    is ProjectReadScopeAdmission.Admitted -> {
                        val permit = ProjectReadPermit(owner, scoped.freshness)
                        state = State.Active(current.scope, permit)
                        ProjectReadAdmission.Active(permit)
                    }
                    is ProjectReadScopeAdmission.Rejected ->
                        ProjectReadAdmission.Rejected(scoped.failure)
                }
                is State.Active -> when (val scoped = current.scope.admit(freshness)) {
                    is ProjectReadScopeAdmission.Admitted -> {
                        val request = QueuedRequest(owner, scoped.freshness)
                        state = State.ActiveAndQueued(current.scope, current.permit, request)
                        ProjectReadAdmission.Queued(request)
                    }
                    is ProjectReadScopeAdmission.Rejected ->
                        ProjectReadAdmission.Rejected(scoped.failure)
                }
                is State.ActiveAndQueued -> when (val scoped = current.scope.admit(freshness)) {
                    is ProjectReadScopeAdmission.Admitted -> ProjectReadAdmission.Rejected(
                        ProjectReadAdmissionFailure.Busy,
                    )
                    is ProjectReadScopeAdmission.Rejected ->
                        ProjectReadAdmission.Rejected(scoped.failure)
                }
                is State.Retired -> ProjectReadAdmission.Rejected(
                    ProjectReadAdmissionFailure.Retired(current.cause),
                )
            }
        }

        /** `ProjectReadPermit -> ProjectReadPermitEnd`; releases exact authority or fails closed. */
        fun release(permit: ProjectReadPermit): ProjectReadPermitEnd =
            endActive(permit, ActivePermitEnd.Release)

        /** `(ProjectReadPermit, cause) -> ProjectReadPermitEnd`; preserves the finite cause. */
        fun cancel(
            permit: ProjectReadPermit,
            cause: ProjectReadCancellationCause,
        ): ProjectReadPermitEnd = endActive(permit, ActivePermitEnd.Cancel(cause))

        /** `ProjectReadPermit -> ProjectReadExecutionAdmission`; refines Active or fails closed. */
        fun beginExecution(permit: ProjectReadPermit): ProjectReadExecutionAdmission =
            synchronized(lock) {
                if (permit.owner !== owner) {
                    return@synchronized ProjectReadExecutionAdmission.Rejected(
                        ProjectReadExecutionAdmissionFailure.NotOwned,
                    )
                }
                when (val lifecycle = permit.lifecycle) {
                    PermitLifecycle.Active -> when (val current = state) {
                        is State.Active -> if (current.permit === permit) {
                            val authority = OwnedExecutingProjectRead(owner, permit)
                            permit.lifecycle = PermitLifecycle.Executing(
                                authority,
                                ExecutionCompletion.NotRequested,
                            )
                            ProjectReadExecutionAdmission.Admitted(authority)
                        } else {
                            ProjectReadExecutionAdmission.Rejected(
                                ProjectReadExecutionAdmissionFailure.NotOwned,
                            )
                        }
                        is State.ActiveAndQueued -> if (current.permit === permit) {
                            val authority = OwnedExecutingProjectRead(owner, permit)
                            permit.lifecycle = PermitLifecycle.Executing(
                                authority,
                                ExecutionCompletion.NotRequested,
                            )
                            ProjectReadExecutionAdmission.Admitted(authority)
                        } else {
                            ProjectReadExecutionAdmission.Rejected(
                                ProjectReadExecutionAdmissionFailure.NotOwned,
                            )
                        }
                        is State.Idle, is State.Retired -> ProjectReadExecutionAdmission.Rejected(
                            ProjectReadExecutionAdmissionFailure.NotOwned,
                        )
                    }
                    is PermitLifecycle.Executing -> ProjectReadExecutionAdmission.Rejected(
                        ProjectReadExecutionAdmissionFailure.AlreadyExecuting,
                    )
                    is PermitLifecycle.Terminal -> ProjectReadExecutionAdmission.Rejected(
                        ProjectReadExecutionAdmissionFailure.Terminal(lifecycle.value),
                    )
                }
            }

        /** `ExecutingProjectRead -> ProjectReadPermitEnd`; releases exact execution or rejects. */
        fun releaseExecution(execution: ExecutingProjectRead): ProjectReadPermitEnd =
            endExecution(execution, ProjectReadPermitTerminal.Released)

        /** `(ExecutingProjectRead, platform cause) -> ProjectReadPermitEnd`; preserves the cause. */
        fun cancelExecution(
            execution: ExecutingProjectRead,
            cause: ProjectReadExecutionCancellationCause,
        ): ProjectReadPermitEnd = endExecution(
            execution,
            ProjectReadPermitTerminal.ExecutionCancelled(cause),
        )

        /** `(ProjectReadPermit, client cause) -> cancellation`; ends or defers, otherwise closes. */
        fun requestExecutionCancellation(
            permit: ProjectReadPermit,
            cause: ProjectReadCancellationCause,
        ): ProjectReadExecutionCancellation = synchronized(lock) {
            if (permit.owner !== owner) return@synchronized ProjectReadExecutionCancellation.NotOwned
            when (val lifecycle = permit.lifecycle) {
                PermitLifecycle.Active -> when (
                    val end = endCurrentPermit(permit, cancelledTerminal(cause))
                ) {
                    is ProjectReadPermitEnd.Ended -> ProjectReadExecutionCancellation.Ended(
                        end.terminal,
                        end.continuation,
                    )
                    is ProjectReadPermitEnd.AlreadyEnded ->
                        ProjectReadExecutionCancellation.AlreadyTerminal(end.terminal)
                    is ProjectReadPermitEnd.Deferred ->
                        ProjectReadExecutionCancellation.AlreadyDeferred(end.terminal)
                    ProjectReadPermitEnd.ExecutionInProgress ->
                        ProjectReadExecutionCancellation.NotOwned
                    ProjectReadPermitEnd.NotOwned -> ProjectReadExecutionCancellation.NotOwned
                }
                is PermitLifecycle.Executing -> when (val request = lifecycle.completion) {
                    ExecutionCompletion.NotRequested -> {
                        permit.lifecycle = PermitLifecycle.Executing(
                            lifecycle.authority,
                            ExecutionCompletion.CancellationRequested(cause),
                        )
                        ProjectReadExecutionCancellation.Deferred(cause)
                    }
                    is ExecutionCompletion.CancellationRequested ->
                        ProjectReadExecutionCancellation.AlreadyDeferred(
                            ProjectReadPermitTerminal.Cancelled(request.cause),
                        )
                }
                is PermitLifecycle.Terminal -> ProjectReadExecutionCancellation.AlreadyTerminal(
                    lifecycle.value,
                )
            }
        }

        /** `(QueuedProjectReadRequest, cause) -> QueuedProjectReadCancellation`; removes once. */
        fun cancelQueued(
            request: QueuedProjectReadRequest,
            cause: ProjectReadCancellationCause,
        ): QueuedProjectReadCancellation = synchronized(lock) {
            if (request.owner !== owner) {
                return@synchronized QueuedProjectReadCancellation.NotOwned
            }
            when (val terminal = request.lifecycle) {
                is QueueLifecycle.Terminal -> QueuedProjectReadCancellation.AlreadyTerminal(
                    terminal.value,
                )
                QueueLifecycle.Pending -> {
                    val current = state
                    if (current !is State.ActiveAndQueued || current.request !== request) {
                        QueuedProjectReadCancellation.NotOwned
                    } else {
                        request.end(QueuedProjectReadTerminal.Cancelled(cause))
                        state = State.Active(current.scope, current.permit)
                        QueuedProjectReadCancellation.Cancelled(cause)
                    }
                }
            }
        }

        /** `QueuedProjectReadRequest -> QueuedProjectReadObservation`; observes without mutation. */
        fun observeQueued(request: QueuedProjectReadRequest): QueuedProjectReadObservation =
            synchronized(lock) {
                if (request.owner !== owner) return@synchronized QueuedProjectReadObservation.NotOwned
                when (val lifecycle = request.lifecycle) {
                    QueueLifecycle.Pending -> if (
                        state is State.ActiveAndQueued &&
                        (state as State.ActiveAndQueued).request === request
                    ) QueuedProjectReadObservation.Pending else QueuedProjectReadObservation.NotOwned
                    is QueueLifecycle.Terminal -> QueuedProjectReadObservation.Terminal(
                        lifecycle.value,
                    )
                }
            }

        /** `ProjectReadRetirementCause -> ProjectReadRetirement`; preserves first cause exactly. */
        fun retire(cause: ProjectReadRetirementCause): ProjectReadRetirement = synchronized(lock) {
            when (val current = state) {
                is State.Idle -> retireFrom(cause, RetiredProjectReadAuthority.None)
                is State.Active -> {
                    current.permit.end(current.permit.retirementTerminal(cause))
                    retireFrom(cause, RetiredProjectReadAuthority.Active(current.permit))
                }
                is State.ActiveAndQueued -> {
                    current.permit.end(current.permit.retirementTerminal(cause))
                    current.request.end(QueuedProjectReadTerminal.Retired(cause))
                    retireFrom(
                        cause,
                        RetiredProjectReadAuthority.ActiveAndQueued(
                            current.permit,
                            current.request,
                        ),
                    )
                }
                is State.Retired -> ProjectReadRetirement.AlreadyRetired(current.cause)
            }
        }

        private fun endActive(
            permit: ProjectReadPermit,
            requestedEnd: ActivePermitEnd,
        ): ProjectReadPermitEnd = synchronized(lock) {
            if (permit.owner !== owner) return@synchronized ProjectReadPermitEnd.NotOwned
            when (val ended = permit.lifecycle) {
                is PermitLifecycle.Terminal -> ProjectReadPermitEnd.AlreadyEnded(ended.value)
                PermitLifecycle.Active -> endCurrentPermit(
                    permit,
                    requestedEnd.terminal(),
                )
                is PermitLifecycle.Executing -> when (requestedEnd) {
                    ActivePermitEnd.Release -> ProjectReadPermitEnd.ExecutionInProgress
                    is ActivePermitEnd.Cancel -> when (val request = ended.completion) {
                        ExecutionCompletion.NotRequested -> {
                            permit.lifecycle = PermitLifecycle.Executing(
                                ended.authority,
                                ExecutionCompletion.CancellationRequested(requestedEnd.cause),
                            )
                            ProjectReadPermitEnd.Deferred(requestedEnd.terminal())
                        }
                        is ExecutionCompletion.CancellationRequested ->
                            ProjectReadPermitEnd.Deferred(
                                ProjectReadPermitTerminal.Cancelled(request.cause),
                            )
                    }
                }
            }
        }
        private fun endExecution(
            execution: ExecutingProjectRead,
            terminal: ProjectReadPermitTerminal,
        ): ProjectReadPermitEnd = synchronized(lock) {
            val owned = execution as? OwnedExecutingProjectRead
                ?: return@synchronized ProjectReadPermitEnd.NotOwned
            if (owned.owner !== owner) return@synchronized ProjectReadPermitEnd.NotOwned
            val permit = owned.permit
            when (val lifecycle = permit.lifecycle) {
                PermitLifecycle.Active -> ProjectReadPermitEnd.NotOwned
                is PermitLifecycle.Executing -> if (lifecycle.authority !== execution) {
                    ProjectReadPermitEnd.NotOwned
                } else when (val request = lifecycle.completion) {
                    ExecutionCompletion.NotRequested -> endCurrentPermit(permit, terminal)
                    is ExecutionCompletion.CancellationRequested -> endCurrentPermit(
                        permit,
                        ProjectReadPermitTerminal.Cancelled(request.cause),
                    )
                }
                is PermitLifecycle.Terminal -> ProjectReadPermitEnd.AlreadyEnded(lifecycle.value)
            }
        }

        private fun endCurrentPermit(
            permit: ProjectReadPermit,
            terminal: ProjectReadPermitTerminal,
        ): ProjectReadPermitEnd = when (val current = state) {
                    is State.Active -> if (current.permit !== permit) {
                        ProjectReadPermitEnd.NotOwned
                    } else {
                        permit.end(terminal)
                        state = State.Idle(current.scope)
                        ProjectReadPermitEnd.Ended(terminal, ProjectReadContinuation.Idle)
                    }
                    is State.ActiveAndQueued -> if (current.permit !== permit) {
                        ProjectReadPermitEnd.NotOwned
                    } else {
                        permit.end(terminal)
                        val promoted = ProjectReadPermit(owner, current.request.freshness)
                        current.request.end(QueuedProjectReadTerminal.Promoted(promoted))
                        state = State.Active(current.scope, promoted)
                        ProjectReadPermitEnd.Ended(
                            terminal,
                            ProjectReadContinuation.Promoted(current.request, promoted),
                        )
                    }
                    is State.Idle, is State.Retired -> ProjectReadPermitEnd.NotOwned
                }

        private fun cancelledTerminal(
            cause: ProjectReadCancellationCause,
        ): ProjectReadPermitTerminal = ProjectReadPermitTerminal.Cancelled(cause)

        private fun ActivePermitEnd.terminal(): ProjectReadPermitTerminal = when (this) {
            ActivePermitEnd.Release -> ProjectReadPermitTerminal.Released
            is ActivePermitEnd.Cancel -> ProjectReadPermitTerminal.Cancelled(cause)
        }

        private fun ProjectReadPermit.retirementTerminal(
            cause: ProjectReadRetirementCause,
        ): ProjectReadPermitTerminal = when (val current = lifecycle) {
            PermitLifecycle.Active -> ProjectReadPermitTerminal.Retired(cause)
            is PermitLifecycle.Executing -> when (val completion = current.completion) {
                ExecutionCompletion.NotRequested -> ProjectReadPermitTerminal.Retired(cause)
                is ExecutionCompletion.CancellationRequested ->
                    ProjectReadPermitTerminal.Cancelled(completion.cause)
            }
            is PermitLifecycle.Terminal -> current.value
        }

        private fun retireFrom(
            cause: ProjectReadRetirementCause,
            authority: RetiredProjectReadAuthority,
        ): ProjectReadRetirement.Retired {
            state = State.Retired(cause)
            return ProjectReadRetirement.Retired(cause, authority)
        }

        private sealed interface State {
            class Idle(val scope: ProjectReadScope) : State
            class Active(val scope: ProjectReadScope, val permit: ProjectReadPermit) : State
            class ActiveAndQueued(
                val scope: ProjectReadScope,
                val permit: ProjectReadPermit,
                val request: QueuedRequest,
            ) : State
            class Retired(val cause: ProjectReadRetirementCause) : State
        }

        private class OwnedExecutingProjectRead(
            val owner: Any,
            val permit: ProjectReadPermit,
        ) : ExecutingProjectRead

        companion object {
            /** `VfsPassiveReadCapability -> ProjectReadSingleFlight`; retains exact scope. */
            fun bind(initialFreshness: VfsPassiveReadCapability): Controller =
                Controller(initialFreshness)
        }
    }
    }
}
