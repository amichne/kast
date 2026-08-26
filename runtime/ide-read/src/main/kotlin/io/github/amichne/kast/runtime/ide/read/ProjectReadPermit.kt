package io.github.amichne.kast.runtime.ide.read

import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability

/**
 * Opaque authority for one admitted semantic read.
 *
 * Construction and lifecycle mutation are private to the nested [Controller]. The handle exposes
 * no retained freshness evidence; the owning controller must validate it for every transition.
 */
class ProjectReadPermit private constructor(
    private val owner: OwnerToken,
    private val freshness: VfsPassiveReadCapability,
) {
    private var lifecycle: PermitLifecycle = PermitLifecycle.Active

    private fun end(terminal: ProjectReadPermitTerminal) {
        check(lifecycle === PermitLifecycle.Active)
        lifecycle = PermitLifecycle.Terminal(terminal)
    }

    private class OwnerToken

    private sealed interface PermitLifecycle {
        data object Active : PermitLifecycle
        class Terminal(val value: ProjectReadPermitTerminal) : PermitLifecycle
    }

    /** Opaque, non-forgeable identity of the sole bounded queued request. */
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

    /**
     * One project-scoped owner of one active read and one bounded queued request.
     *
     * Proof transition: `VfsPassiveReadCapability -> Controller`. The private constructor retains
     * the capability's exact canonical root and epoch-comparison domain as its scope. Construction
     * stays closed until the later hosted project-level owner is introduced.
     *
     * The private lock serializes only this instance. State contains no live Project, callback,
     * collection, channel, executor, or installation registry.
     */
    internal class Controller private constructor(
        initialFreshness: VfsPassiveReadCapability,
    ) {
        private val lock = Any()
        private val owner = OwnerToken()
        private var state: State = State.Idle(ProjectScope.bind(initialFreshness))

        /**
         * Proof transition: `VfsPassiveReadCapability -> ProjectReadAdmission`.
         *
         * Matching scope becomes the sole permit, the sole queue position, or finite Busy.
         * Mismatched root, incomparable source, or retirement fails closed without mutation.
         */
        fun admit(freshness: VfsPassiveReadCapability): ProjectReadAdmission = synchronized(lock) {
            when (val current = state) {
                is State.Idle -> when (val scoped = current.scope.admit(freshness)) {
                    is ProjectScopeAdmission.Admitted -> {
                        val permit = ProjectReadPermit(owner, scoped.freshness)
                        state = State.Active(current.scope, permit)
                        ProjectReadAdmission.Active(permit)
                    }
                    is ProjectScopeAdmission.Rejected ->
                        ProjectReadAdmission.Rejected(scoped.failure)
                }
                is State.Active -> when (val scoped = current.scope.admit(freshness)) {
                    is ProjectScopeAdmission.Admitted -> {
                        val request = QueuedRequest(owner, scoped.freshness)
                        state = State.ActiveAndQueued(current.scope, current.permit, request)
                        ProjectReadAdmission.Queued(request)
                    }
                    is ProjectScopeAdmission.Rejected ->
                        ProjectReadAdmission.Rejected(scoped.failure)
                }
                is State.ActiveAndQueued -> when (val scoped = current.scope.admit(freshness)) {
                    is ProjectScopeAdmission.Admitted -> ProjectReadAdmission.Rejected(
                        ProjectReadAdmissionFailure.Busy,
                    )
                    is ProjectScopeAdmission.Rejected ->
                        ProjectReadAdmission.Rejected(scoped.failure)
                }
                is State.Retired -> ProjectReadAdmission.Rejected(
                    ProjectReadAdmissionFailure.Retired(current.cause),
                )
            }
        }

        /**
         * Proof transition: `ProjectReadPermit -> ProjectReadPermitEnd`.
         *
         * Ends owned active authority exactly once as Released and promotes at most one queue.
         * Foreign and repeated terminalization remain closed outcomes.
         */
        fun release(permit: ProjectReadPermit): ProjectReadPermitEnd =
            endActive(permit, ProjectReadPermitTerminal.Released)

        /**
         * Proof transition: `(ProjectReadPermit, ProjectReadCancellationCause) ->
         * ProjectReadPermitEnd`.
         *
         * Ends owned active authority once with the finite cause and promotes at most one queue.
         */
        fun cancel(
            permit: ProjectReadPermit,
            cause: ProjectReadCancellationCause,
        ): ProjectReadPermitEnd = endActive(permit, ProjectReadPermitTerminal.Cancelled(cause))

        /**
         * Proof transition: `(QueuedProjectReadRequest, ProjectReadCancellationCause) ->
         * QueuedProjectReadCancellation`.
         *
         * Removes the exact pending request once. Terminal and foreign candidates remain data.
         */
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

        /**
         * Proof transition: `ProjectReadRetirementCause -> ProjectReadRetirement`.
         *
         * Terminalizes every retained authority, enters Retired, and preserves the first cause.
         */
        fun retire(cause: ProjectReadRetirementCause): ProjectReadRetirement = synchronized(lock) {
            when (val current = state) {
                is State.Idle -> retireFrom(cause, RetiredProjectReadAuthority.None)
                is State.Active -> {
                    current.permit.end(ProjectReadPermitTerminal.Retired(cause))
                    retireFrom(cause, RetiredProjectReadAuthority.Active(current.permit))
                }
                is State.ActiveAndQueued -> {
                    current.permit.end(ProjectReadPermitTerminal.Retired(cause))
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
            terminal: ProjectReadPermitTerminal,
        ): ProjectReadPermitEnd = synchronized(lock) {
            if (permit.owner !== owner) return@synchronized ProjectReadPermitEnd.NotOwned
            when (val ended = permit.lifecycle) {
                is PermitLifecycle.Terminal -> ProjectReadPermitEnd.AlreadyEnded(ended.value)
                PermitLifecycle.Active -> when (val current = state) {
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
            }
        }

        private fun retireFrom(
            cause: ProjectReadRetirementCause,
            authority: RetiredProjectReadAuthority,
        ): ProjectReadRetirement.Retired {
            state = State.Retired(cause)
            return ProjectReadRetirement.Retired(cause, authority)
        }

        private sealed interface State {
            class Idle(val scope: ProjectScope) : State
            class Active(val scope: ProjectScope, val permit: ProjectReadPermit) : State
            class ActiveAndQueued(
                val scope: ProjectScope,
                val permit: ProjectReadPermit,
                val request: QueuedRequest,
            ) : State
            class Retired(val cause: ProjectReadRetirementCause) : State
        }

        /** Bound exact-root and epoch-source identity for one controller. */
        private class ProjectScope(
            private val canonicalRoot: CanonicalWorkspaceRoot,
            private val comparisonEpoch: ProjectReadEpoch<*>,
        ) {
            /**
             * Proof transition: `VfsPassiveReadCapability -> ProjectScopeAdmission`.
             *
             * Establishes exact root and comparable source, or returns one closed scope failure.
             */
            fun admit(freshness: VfsPassiveReadCapability): ProjectScopeAdmission = when {
                freshness.canonicalRoot != canonicalRoot -> ProjectScopeAdmission.Rejected(
                    ProjectReadAdmissionFailure.WrongProject,
                )
                comparisonEpoch.relationTo(freshness.admittedEpoch) ==
                    ProjectReadEpochRelation.INCOMPARABLE -> ProjectScopeAdmission.Rejected(
                        ProjectReadAdmissionFailure.IncomparableProjectSource,
                    )
                else -> ProjectScopeAdmission.Admitted(freshness)
            }

            companion object {
                /**
                 * Proof transition: `VfsPassiveReadCapability -> ProjectScope`.
                 *
                 * Retains exact root and epoch domain. Raw evidence cannot leave this controller.
                 */
                fun bind(freshness: VfsPassiveReadCapability): ProjectScope = ProjectScope(
                    freshness.canonicalRoot,
                    freshness.admittedEpoch,
                )
            }
        }

        /** Closed refinement of freshness evidence into this controller's project scope. */
        private sealed interface ProjectScopeAdmission {
            class Admitted(val freshness: VfsPassiveReadCapability) : ProjectScopeAdmission
            class Rejected(val failure: ProjectReadAdmissionFailure) : ProjectScopeAdmission
        }
    }
    }
}
