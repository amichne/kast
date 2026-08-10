package io.github.amichne.kast.idea

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.transition.TransitionBlocker
import io.github.amichne.kast.idea.transition.WorkspaceLifecycle
import io.github.amichne.kast.idea.transition.WorkspaceSourceFreshnessCoverage
import io.github.amichne.kast.idea.transition.WorkspaceTransitionRequest
import io.github.amichne.kast.idea.transition.WorkspaceTransitionSnapshot
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState

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
            val manifest: PublishedWorkspaceGenerationManifest,
        ) : Join
    }

    data class Rejected(
        val failure: WorkspaceTransitionRequestFailure,
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
         * [WorkspaceTransitionRequestFailure] rejection.
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
                                Join.Published(admission.manifest)
                            }
                    }

                    WorkspaceSourceFreshnessCoverage.Uncovered -> Enqueue(admission.baseline)
                }
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

internal sealed interface WorkspaceTransitionRequestFailure {
    data class SemanticAdmissionFailed(val detail: String) : WorkspaceTransitionRequestFailure

    fun toConflict(): ConflictException = when (this) {
        is SemanticAdmissionFailed -> ConflictException(
            message = "Workspace transition request cannot recover failed semantic admission",
            details = mapOf("admissionState" to "FAILED", "detail" to detail),
        )
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
            val manifest: PublishedWorkspaceGenerationManifest,
            val observedBaseline: PublishedWorkspaceGenerationState,
        ) : Permitted {
            override val baseline: PublishedWorkspaceGenerationState =
                PublishedWorkspaceGenerationState.Published(manifest)
        }
    }

    data class Rejected(
        val failure: WorkspaceTransitionRequestFailure,
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
                manifest = status.generation,
                observedBaseline = TransitionPublicationBaseline.derive(observation),
            )

            is IdeaIndexSemanticAdmission.Status.Pending -> Permitted.Pending(
                TransitionPublicationBaseline.derive(observation),
            )

            is IdeaIndexSemanticAdmission.Status.Failed -> Rejected(
                WorkspaceTransitionRequestFailure.SemanticAdmissionFailed(status.detail),
            )
        }
    }
}

internal sealed interface WorkspaceTransitionCompletion {
    data class Ready(val manifest: PublishedWorkspaceGenerationManifest) : WorkspaceTransitionCompletion

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
                is PublishedWorkspaceGenerationState.Published -> Ready(published.manifest)
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
