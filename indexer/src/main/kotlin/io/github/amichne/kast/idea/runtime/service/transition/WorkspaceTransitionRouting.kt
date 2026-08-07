package io.github.amichne.kast.idea

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.transition.TransitionBlocker
import io.github.amichne.kast.idea.transition.WorkspaceLifecycle
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceTransitionSnapshot
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState

internal sealed interface TransitionObservation {
    data object Unobserved : TransitionObservation

    data class Observed(val snapshot: WorkspaceTransitionSnapshot) : TransitionObservation
}

internal sealed interface WorkspaceTransitionRoute {
    data class Request(
        val baseline: PublishedWorkspaceGenerationState,
    ) : WorkspaceTransitionRoute

    data class Join(
        val baseline: PublishedWorkspaceGenerationState,
    ) : WorkspaceTransitionRoute

    data class Rejected(
        val failure: WorkspaceTransitionJoinFailure,
    ) : WorkspaceTransitionRoute

    companion object {
        /**
         * Proof transition:
         * `(WorkspaceSignal, IdeaIndexSemanticAdmission.Status, TransitionObservation)`
         * `-> WorkspaceTransitionRoute`.
         *
         * A source request retains the observed publication and joins only an
         * active reconciliation. Recovery audits remain non-joinable because
         * an unrelated cycle does not prove that audit semantics ran; they
         * request a superseding cycle. Pending admission retains the most
         * recent publication baseline and requests work when no compatible
         * cycle exists. Failed admission becomes finite rejection data.
         */
        fun derive(
            signal: WorkspaceSignal,
            status: IdeaIndexSemanticAdmission.Status,
            observation: TransitionObservation,
        ): WorkspaceTransitionRoute {
            val admission = TransitionRequestAdmission.derive(status, observation)
            return when (admission) {
                is TransitionRequestAdmission.Rejected -> Rejected(admission.failure)
                is TransitionRequestAdmission.Permitted -> {
                    val activity = TransitionActivity.derive(observation)
                    if (signal == WorkspaceSignal.Source && activity is TransitionActivity.Active) {
                        Join(activity.snapshot.published)
                    } else {
                        Request(admission.baseline)
                    }
                }
            }
        }
    }
}

internal sealed interface WorkspaceTransitionJoinFailure {
    data class SemanticAdmissionFailed(val detail: String) : WorkspaceTransitionJoinFailure

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
        val failure: WorkspaceTransitionJoinFailure,
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
                WorkspaceTransitionJoinFailure.SemanticAdmissionFailed(status.detail),
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

private sealed interface TransitionActivity {
    data object Inactive : TransitionActivity

    data class Active(val snapshot: WorkspaceTransitionSnapshot) : TransitionActivity

    companion object {
        /**
         * Proof transition: `TransitionObservation -> TransitionActivity`.
         *
         * Refines an observed lifecycle into a closed active/inactive state.
         * Callers cannot mistake an absent or terminal observation for a
         * joinable reconciliation.
         */
        fun derive(observation: TransitionObservation): TransitionActivity = when (observation) {
            TransitionObservation.Unobserved -> Inactive
            is TransitionObservation.Observed -> when (observation.snapshot.lifecycle) {
                WorkspaceLifecycle.Dirty,
                WorkspaceLifecycle.Settling,
                WorkspaceLifecycle.Refreshing,
                WorkspaceLifecycle.Reconciling,
                WorkspaceLifecycle.Verifying,
                -> Active(observation.snapshot)

                WorkspaceLifecycle.Ready,
                WorkspaceLifecycle.Blocked,
                -> Inactive
            }
        }
    }
}
