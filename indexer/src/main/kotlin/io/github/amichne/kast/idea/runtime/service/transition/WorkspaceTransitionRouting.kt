package io.github.amichne.kast.idea

import io.github.amichne.kast.workspace.contract.TransitionBlocker
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.WorkspaceLifecycle
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshnessCoverage
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionSnapshot
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionFailure

/** Observes the current compiler-backed admission without owning mutation or publication effects. */
internal fun interface WorkspaceTransitionAdmissionObservation {
    fun status(): IdeaIndexSemanticAdmission.Status
}

/** Retains successful dispatch or a finite ingress failure without nullable control state. */
internal sealed interface WorkspaceTransitionDispatch {
    data object Dispatched : WorkspaceTransitionDispatch

    data class Rejected(
        val failure: WorkspaceTransitionFailure,
    ) : WorkspaceTransitionDispatch
}

internal sealed interface TransitionObservation {
    data object Unobserved : TransitionObservation

    data class Observed(val snapshot: WorkspaceTransitionSnapshot) : TransitionObservation
}

internal sealed interface WorkspaceTransitionRoute {
    data class Enqueue(
        val baseline: PublishedWorkspaceGenerationState,
    ) : WorkspaceTransitionRoute

    sealed interface Join : WorkspaceTransitionRoute {
        data class Awaiting(
            val baseline: PublishedWorkspaceGenerationState,
        ) : Join

        data class Published(
            val publication: PublishedWorkspaceGeneration,
        ) : Join
    }

    data class Rejected(
        val failure: WorkspaceTransitionFailure,
    ) : WorkspaceTransitionRoute

    companion object {
        /**
         * Proof transition:
         * `(IdeaIndexSemanticAdmission.Status, TransitionObservation, WorkspaceTransitionRequest)`
         * `-> WorkspaceTransitionRoute`.
         *
         * Every admitted request retains its publication baseline. Exact
         * source claims already covered by the active cycle become [Join]; all
         * unkeyed, changed, or disjoint work becomes [Enqueue] so it cannot be
         * incorrectly treated as covered. Failed admission becomes the finite
         * [WorkspaceTransitionRequestFailure] rejection. The returned route
         * may be unpacked only at the ingress dispatch and waiter-registration
         * boundary.
         */
        fun derive(
            status: IdeaIndexSemanticAdmission.Status,
            observation: TransitionObservation,
            request: WorkspaceTransitionRequest,
        ): WorkspaceTransitionRoute {
            val admission = TransitionRequestAdmission.derive(status, observation)
            return when (admission) {
                is TransitionRequestAdmission.Rejected -> Rejected(admission.failure)
                is TransitionRequestAdmission.Permitted -> when (
                    ActiveSourceRequestCoverage.derive(observation, request)
                ) {
                    WorkspaceSourceFreshnessCoverage.Covered -> when (admission) {
                        is TransitionRequestAdmission.Permitted.Pending -> Join.Awaiting(admission.baseline)
                        is TransitionRequestAdmission.Permitted.Ready ->
                            if (admission.baseline == admission.observedBaseline) {
                                Join.Awaiting(admission.observedBaseline)
                            } else {
                                Join.Published(admission.publication)
                            }
                    }

                    WorkspaceSourceFreshnessCoverage.Uncovered -> Enqueue(admission.baseline)
                }
            }
        }
    }
}

internal sealed interface WorkspaceTransitionJoinRegistration {
    data object Awaiting : WorkspaceTransitionJoinRegistration

    data class Published(
        val publication: PublishedWorkspaceGeneration,
    ) : WorkspaceTransitionJoinRegistration

    data class Blocked(
        val blocker: TransitionBlocker,
    ) : WorkspaceTransitionJoinRegistration

    data class Invalid(
        val lifecycle: WorkspaceLifecycle,
    ) : WorkspaceTransitionJoinRegistration

    companion object {
        /**
         * Proof transition:
         * `(WorkspaceTransitionRoute.Join.Awaiting, TransitionObservation)`
         * `-> WorkspaceTransitionJoinRegistration`.
         *
         * Retains a publication, blocker, or invalid terminal state observed
         * after a covered route was derived but before its waiter was
         * registered. The finite result may be unpacked only at the ingress
         * waiter-registration boundary.
         */
        fun derive(
            join: WorkspaceTransitionRoute.Join.Awaiting,
            observation: TransitionObservation,
        ): WorkspaceTransitionJoinRegistration = when (observation) {
            TransitionObservation.Unobserved -> Awaiting
            is TransitionObservation.Observed -> when (
                val completion = WorkspaceTransitionCompletion.derive(observation.snapshot)
            ) {
                is WorkspaceTransitionCompletion.Ready -> {
                    val published = PublishedWorkspaceGenerationState.Published(completion.publication)
                    if (published == join.baseline) Awaiting else Published(completion.publication)
                }

                is WorkspaceTransitionCompletion.Blocked -> Blocked(completion.blocker)
                is WorkspaceTransitionCompletion.Invalid -> Invalid(completion.lifecycle)
                WorkspaceTransitionCompletion.InProgress -> Awaiting
            }
        }
    }
}

private object ActiveSourceRequestCoverage {
    /**
     * Proof transition:
     * `(TransitionObservation, WorkspaceTransitionRequest)`
     * `-> WorkspaceSourceFreshnessCoverage`.
     *
     * Only claims retained by the currently observed active cycle can cover a
     * request. Unobserved and inactive snapshots fail closed as uncovered.
     */
    fun derive(
        observation: TransitionObservation,
        request: WorkspaceTransitionRequest,
    ): WorkspaceSourceFreshnessCoverage = when (observation) {
        TransitionObservation.Unobserved -> WorkspaceSourceFreshnessCoverage.Uncovered
        is TransitionObservation.Observed -> when (observation.snapshot.lifecycle) {
            WorkspaceLifecycle.Refreshing,
            WorkspaceLifecycle.Reconciling,
            WorkspaceLifecycle.Verifying,
                -> observation.snapshot.activeSourceFreshness.coverageOf(request)

            WorkspaceLifecycle.Ready,
            WorkspaceLifecycle.Dirty,
            WorkspaceLifecycle.Settling,
            WorkspaceLifecycle.Blocked,
                -> WorkspaceSourceFreshnessCoverage.Uncovered
        }
    }
}

private object TransitionPublicationBaseline {
    /**
     * Proof transition: `TransitionObservation -> PublishedWorkspaceGenerationState`.
     *
     * Retains the last observed publication as the lower bound a requested
     * cycle must advance. Before the first observation, [PublishedWorkspaceGenerationState.Unpublished]
     * is the only honest baseline.
     */
    fun derive(observation: TransitionObservation): PublishedWorkspaceGenerationState = when (observation) {
        TransitionObservation.Unobserved -> PublishedWorkspaceGenerationState.Unpublished
        is TransitionObservation.Observed -> observation.snapshot.published
    }
}

private sealed interface TransitionRequestAdmission {
    sealed interface Permitted : TransitionRequestAdmission {
        val baseline: PublishedWorkspaceGenerationState

        data class Pending(
            override val baseline: PublishedWorkspaceGenerationState,
        ) : Permitted

        data class Ready(
            val publication: PublishedWorkspaceGeneration,
            val observedBaseline: PublishedWorkspaceGenerationState,
        ) : Permitted {
            override val baseline: PublishedWorkspaceGenerationState =
                PublishedWorkspaceGenerationState.Published(publication)
        }
    }

    data class Rejected(
        val failure: WorkspaceTransitionFailure,
    ) : TransitionRequestAdmission

    companion object {
        /**
         * Proof transition:
         * `(IdeaIndexSemanticAdmission.Status, TransitionObservation)`
         * `-> TransitionRequestAdmission`.
         *
         * READY retains both its published generation and the observed
         * transition baseline. Equality proves that READY is stale relative to
         * an active observation and must still await that cycle; inequality
         * proves publication won the observation race. PENDING retains the
         * observed baseline. FAILED retains a finite rejection and cannot be
         * reinterpreted as joinable by a stale lifecycle observation.
         */
        fun derive(
            status: IdeaIndexSemanticAdmission.Status,
            observation: TransitionObservation,
        ): TransitionRequestAdmission = when (status) {
            is IdeaIndexSemanticAdmission.Status.Ready -> Permitted.Ready(
                publication = status.generation,
                observedBaseline = TransitionPublicationBaseline.derive(observation),
            )

            is IdeaIndexSemanticAdmission.Status.Pending -> Permitted.Pending(
                TransitionPublicationBaseline.derive(observation),
            )

            is IdeaIndexSemanticAdmission.Status.Failed -> Rejected(
                WorkspaceTransitionFailure.SemanticAdmissionFailed(status.detail),
            )
        }
    }
}

internal sealed interface WorkspaceTransitionCompletion {
    data class Ready(val publication: PublishedWorkspaceGeneration) : WorkspaceTransitionCompletion

    data class Blocked(val blocker: TransitionBlocker) : WorkspaceTransitionCompletion

    data object InProgress : WorkspaceTransitionCompletion

    data class Invalid(val lifecycle: WorkspaceLifecycle) : WorkspaceTransitionCompletion

    companion object {
        /**
         * Proof transition: `WorkspaceTransitionSnapshot -> WorkspaceTransitionCompletion`.
         *
         * Refines the snapshot's independently nullable and discriminated wire
         * fields into an exhaustive completion state. READY without a published
         * generation and BLOCKED without a blocker fail closed as [Invalid].
         */
        fun derive(snapshot: WorkspaceTransitionSnapshot): WorkspaceTransitionCompletion = when (snapshot.lifecycle) {
            WorkspaceLifecycle.Ready -> when (val published = snapshot.published) {
                PublishedWorkspaceGenerationState.Unpublished -> Invalid(snapshot.lifecycle)
                is PublishedWorkspaceGenerationState.Published -> Ready(published.publication)
            }

            WorkspaceLifecycle.Blocked -> snapshot.blocker
                                              ?.let(::Blocked)
                                          ?: Invalid(snapshot.lifecycle)

            WorkspaceLifecycle.Dirty,
            WorkspaceLifecycle.Settling,
            WorkspaceLifecycle.Refreshing,
            WorkspaceLifecycle.Reconciling,
            WorkspaceLifecycle.Verifying,
                -> InProgress
        }
    }
}
