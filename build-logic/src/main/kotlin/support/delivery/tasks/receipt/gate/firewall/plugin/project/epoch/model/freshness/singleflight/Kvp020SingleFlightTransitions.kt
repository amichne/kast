package support.delivery

import kotlinx.serialization.Serializable

@Serializable
internal enum class Kvp020SingleFlightTransition {
    IDLE_ADMIT_MATCHING_ISSUES_ACTIVE_TO_ACTIVE,
    ACTIVE_ADMIT_MATCHING_QUEUES_TO_ACTIVE_AND_QUEUED,
    ACTIVE_AND_QUEUED_ADMIT_MATCHING_REJECTS_BUSY_NO_MUTATION,

    IDLE_ADMIT_WRONG_PROJECT_REJECTS_SCOPE_NO_MUTATION,
    IDLE_ADMIT_INCOMPARABLE_SOURCE_REJECTS_SCOPE_NO_MUTATION,
    ACTIVE_ADMIT_WRONG_PROJECT_REJECTS_SCOPE_NO_MUTATION,
    ACTIVE_ADMIT_INCOMPARABLE_SOURCE_REJECTS_SCOPE_NO_MUTATION,
    ACTIVE_AND_QUEUED_ADMIT_WRONG_PROJECT_REJECTS_SCOPE_NO_MUTATION,
    ACTIVE_AND_QUEUED_ADMIT_INCOMPARABLE_SOURCE_REJECTS_SCOPE_NO_MUTATION,

    ACTIVE_RELEASE_ENDS_ONCE_TO_IDLE,
    ACTIVE_CANCEL_REQUEST_CANCELLED_ENDS_ONCE_TO_IDLE,
    ACTIVE_CANCEL_CLIENT_DISCONNECTED_ENDS_ONCE_TO_IDLE,
    ACTIVE_AND_QUEUED_RELEASE_ENDS_ONCE_PROMOTES_ONCE_TO_ACTIVE,
    ACTIVE_AND_QUEUED_CANCEL_REQUEST_CANCELLED_ENDS_ONCE_PROMOTES_ONCE_TO_ACTIVE,
    ACTIVE_AND_QUEUED_CANCEL_CLIENT_DISCONNECTED_ENDS_ONCE_PROMOTES_ONCE_TO_ACTIVE,
    ACTIVE_AND_QUEUED_CANCEL_QUEUED_REQUEST_CANCELLED_TERMINALIZES_ONCE_TO_ACTIVE,
    ACTIVE_AND_QUEUED_CANCEL_QUEUED_CLIENT_DISCONNECTED_TERMINALIZES_ONCE_TO_ACTIVE,

    IDLE_RETIRE_PROJECT_DISPOSED_TO_RETIRED,
    IDLE_RETIRE_PLUGIN_UNLOADED_TO_RETIRED,
    IDLE_RETIRE_ENDPOINT_PUBLICATION_FAILED_TO_RETIRED,
    IDLE_RETIRE_SOCKET_FAILED_TO_RETIRED,
    ACTIVE_RETIRE_PROJECT_DISPOSED_TERMINALIZES_ACTIVE_TO_RETIRED,
    ACTIVE_RETIRE_PLUGIN_UNLOADED_TERMINALIZES_ACTIVE_TO_RETIRED,
    ACTIVE_RETIRE_ENDPOINT_PUBLICATION_FAILED_TERMINALIZES_ACTIVE_TO_RETIRED,
    ACTIVE_RETIRE_SOCKET_FAILED_TERMINALIZES_ACTIVE_TO_RETIRED,
    ACTIVE_AND_QUEUED_RETIRE_PROJECT_DISPOSED_TERMINALIZES_BOTH_TO_RETIRED,
    ACTIVE_AND_QUEUED_RETIRE_PLUGIN_UNLOADED_TERMINALIZES_BOTH_TO_RETIRED,
    ACTIVE_AND_QUEUED_RETIRE_ENDPOINT_PUBLICATION_FAILED_TERMINALIZES_BOTH_TO_RETIRED,
    ACTIVE_AND_QUEUED_RETIRE_SOCKET_FAILED_TERMINALIZES_BOTH_TO_RETIRED,

    RETIRED_ADMIT_REJECTS_RETAINING_FIRST_CAUSE_NO_MUTATION,
    RETIRED_RETIRE_REPEATS_RETAINING_FIRST_CAUSE_NO_MUTATION,
}

internal fun canonicalKvp020SingleFlightTransitions(): List<Kvp020SingleFlightTransition> =
    Kvp020SingleFlightTransition.entries

/**
 * Proof transition: `(String, Kvp020ReportPredecessors) ->
 * Kvp020SingleFlightReportMutationVerification`.
 *
 * Establishes that duplicating one transition cannot retain report admission and is rejected only
 * as [Kvp020SingleFlightReportFailure.TRANSITION_SET_MISMATCH]. Raw JSON enters only at the Gradle
 * report mutation boundary.
 */
internal fun verifyKvp020TransitionSetMutation(
    canonical: String,
    predecessors: Kvp020ReportPredecessors,
): Kvp020SingleFlightReportMutationVerification {
    val expected = Kvp020SingleFlightReportFailure.TRANSITION_SET_MISMATCH
    val mutated = canonical.replaceFirst(
        "\"IDLE_ADMIT_MATCHING_ISSUES_ACTIVE_TO_ACTIVE\"",
        "\"ACTIVE_ADMIT_MATCHING_QUEUES_TO_ACTIVE_AND_QUEUED\"",
    )
    return when (val admission = AdmittedKvp020SingleFlightReport.admit(mutated, predecessors)) {
        is Kvp020SingleFlightReportAdmission.Admitted ->
            Kvp020SingleFlightReportMutationVerification.Rejected(
                Kvp020SingleFlightReportMutationFailure.MutationAdmitted(
                    KVP020_TRANSITION_MUTATION_INDEX,
                    expected,
                ),
            )
        is Kvp020SingleFlightReportAdmission.Rejected -> if (admission.failure == expected) {
            Kvp020SingleFlightReportMutationVerification.Complete
        } else {
            Kvp020SingleFlightReportMutationVerification.Rejected(
                Kvp020SingleFlightReportMutationFailure.WrongFailure(
                    KVP020_TRANSITION_MUTATION_INDEX,
                    expected,
                    admission.failure,
                ),
            )
        }
    }
}

private const val KVP020_TRANSITION_MUTATION_INDEX = 20
