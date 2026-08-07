package io.github.amichne.kast.idea

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.transition.TransitionBlocker
import io.github.amichne.kast.idea.transition.WorkspaceLifecycle
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

    data class Rejected(
        val failure: WorkspaceTransitionRequestFailure,
    ) : WorkspaceTransitionRoute

    companion object {
        /**
         * Proof transition:
         * `(IdeaIndexSemanticAdmission.Status, TransitionObservation)`
         * `-> WorkspaceTransitionRoute`.
         *
         * Every admitted request retains its publication baseline and enqueues
         * its semantic signal. The waiter still shares the single transition
         * publication lane, while enqueueing ensures that a request arriving
         * after the active cycle's VFS refresh invalidates that cycle instead
         * of being incorrectly treated as covered. Failed admission becomes
         * finite rejection data.
         */
        fun derive(
            status: IdeaIndexSemanticAdmission.Status,
            observation: TransitionObservation,
        ): WorkspaceTransitionRoute {
            val admission = TransitionRequestAdmission.derive(status, observation)
            return when (admission) {
                is TransitionRequestAdmission.Rejected -> Rejected(admission.failure)
                is TransitionRequestAdmission.Permitted -> Enqueue(admission.baseline)
            }
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
    data class Permitted(
        val baseline: PublishedWorkspaceGenerationState,
    ) : TransitionRequestAdmission

    data class Rejected(
        val failure: WorkspaceTransitionRequestFailure,
    ) : TransitionRequestAdmission

    companion object {
        /**
         * Proof transition:
         * `(IdeaIndexSemanticAdmission.Status, TransitionObservation)`
         * `-> TransitionRequestAdmission`.
         *
         * READY and PENDING retain a publication baseline suitable for a
         * request or compatible join. FAILED retains a finite rejection and
         * cannot be reinterpreted as joinable by a stale lifecycle observation.
         */
        fun derive(
            status: IdeaIndexSemanticAdmission.Status,
            observation: TransitionObservation,
        ): TransitionRequestAdmission = when (status) {
            is IdeaIndexSemanticAdmission.Status.Ready -> Permitted(
                PublishedWorkspaceGenerationState.Published(status.generation),
            )

            is IdeaIndexSemanticAdmission.Status.Pending -> Permitted(
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
