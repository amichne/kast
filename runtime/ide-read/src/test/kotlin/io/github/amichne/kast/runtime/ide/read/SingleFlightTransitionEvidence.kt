package io.github.amichne.kast.runtime.ide.read

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue

/** Executes every transition claimed by the generated KVP-020 report in canonical order. */
internal fun observeKvp020SingleFlightTransitions(): List<String> {
    val observed = mutableListOf<String>()
    observeAdmissions(observed)
    observeActiveEnds(observed)
    observeQueuedEnds(observed)
    observeRetirements(observed)
    observeRetiredTransitions(observed)
    assertEquals(31, observed.size)
    assertEquals(observed.size, observed.toSet().size)
    return observed.toList()
}

private fun observeAdmissions(observed: MutableList<String>) {
    val matching = evidenceFreshness("matching")
    val matchingController = controller(matching.capability())
    val matchingPermit = active(matchingController.admit(matching.capability()))
    val matchingQueue = queued(matchingController.admit(matching.capability()))
    observed += "IDLE_ADMIT_MATCHING_ISSUES_ACTIVE_TO_ACTIVE"
    assertEquals(
        ProjectReadAdmission.Rejected(ProjectReadAdmissionFailure.Busy),
        matchingController.admit(matching.capability()),
    )
    observed += "ACTIVE_ADMIT_MATCHING_QUEUES_TO_ACTIVE_AND_QUEUED"
    assertEquals(
        QueuedProjectReadCancellation.Cancelled(ProjectReadCancellationCause.REQUEST_CANCELLED),
        matchingController.cancelQueued(
            matchingQueue,
            ProjectReadCancellationCause.REQUEST_CANCELLED,
        ),
    )
    assertTrue(matchingController.admit(matching.capability()) is ProjectReadAdmission.Queued)
    assertTrue(matchingController.release(matchingPermit) is ProjectReadPermitEnd.Ended)
    observed += "ACTIVE_AND_QUEUED_ADMIT_MATCHING_REJECTS_BUSY_NO_MUTATION"

    observeIdleScopeRejection(
        observed,
        "idle-wrong",
        ProjectReadAdmissionFailure.WrongProject,
        evidenceFreshness("idle-wrong-foreign"),
        "IDLE_ADMIT_WRONG_PROJECT_REJECTS_SCOPE_NO_MUTATION",
    )
    observeIdleScopeRejection(
        observed,
        "idle-incomparable",
        ProjectReadAdmissionFailure.IncomparableProjectSource,
        evidenceFreshness("idle-incomparable"),
        "IDLE_ADMIT_INCOMPARABLE_SOURCE_REJECTS_SCOPE_NO_MUTATION",
    )
    observeActiveScopeRejection(
        observed,
        "active-wrong",
        ProjectReadAdmissionFailure.WrongProject,
        evidenceFreshness("active-wrong-foreign"),
        "ACTIVE_ADMIT_WRONG_PROJECT_REJECTS_SCOPE_NO_MUTATION",
    )
    observeActiveScopeRejection(
        observed,
        "active-incomparable",
        ProjectReadAdmissionFailure.IncomparableProjectSource,
        evidenceFreshness("active-incomparable"),
        "ACTIVE_ADMIT_INCOMPARABLE_SOURCE_REJECTS_SCOPE_NO_MUTATION",
    )
    observeFullScopeRejection(
        observed,
        "full-wrong",
        ProjectReadAdmissionFailure.WrongProject,
        evidenceFreshness("full-wrong-foreign"),
        "ACTIVE_AND_QUEUED_ADMIT_WRONG_PROJECT_REJECTS_SCOPE_NO_MUTATION",
    )
    observeFullScopeRejection(
        observed,
        "full-incomparable",
        ProjectReadAdmissionFailure.IncomparableProjectSource,
        evidenceFreshness("full-incomparable"),
        "ACTIVE_AND_QUEUED_ADMIT_INCOMPARABLE_SOURCE_REJECTS_SCOPE_NO_MUTATION",
    )
}

private fun observeIdleScopeRejection(
    observed: MutableList<String>,
    tag: String,
    failure: ProjectReadAdmissionFailure,
    rejected: FreshnessFixture,
    claim: String,
) {
    val owned = evidenceFreshness(tag)
    val controller = controller(owned.capability())
    assertEquals(ProjectReadAdmission.Rejected(failure), controller.admit(rejected.capability()))
    assertTrue(controller.admit(owned.capability()) is ProjectReadAdmission.Active)
    observed += claim
}

private fun observeActiveScopeRejection(
    observed: MutableList<String>,
    tag: String,
    failure: ProjectReadAdmissionFailure,
    rejected: FreshnessFixture,
    claim: String,
) {
    val state = activeEvidence(tag)
    assertEquals(
        ProjectReadAdmission.Rejected(failure),
        state.controller.admit(rejected.capability()),
    )
    val request = queued(state.controller.admit(state.freshness.capability()))
    val end = state.controller.release(state.permit) as ProjectReadPermitEnd.Ended
    assertSame(request, (end.continuation as ProjectReadContinuation.Promoted).request)
    observed += claim
}

private fun observeFullScopeRejection(
    observed: MutableList<String>,
    tag: String,
    failure: ProjectReadAdmissionFailure,
    rejected: FreshnessFixture,
    claim: String,
) {
    val state = fullEvidence(tag)
    assertEquals(
        ProjectReadAdmission.Rejected(failure),
        state.controller.admit(rejected.capability()),
    )
    assertEquals(
        QueuedProjectReadCancellation.Cancelled(ProjectReadCancellationCause.REQUEST_CANCELLED),
        state.controller.cancelQueued(
            state.request,
            ProjectReadCancellationCause.REQUEST_CANCELLED,
        ),
    )
    assertEquals(
        ProjectReadPermitEnd.Ended(ProjectReadPermitTerminal.Released, ProjectReadContinuation.Idle),
        state.controller.release(state.permit),
    )
    observed += claim
}

private fun observeActiveEnds(observed: MutableList<String>) {
    val release = activeEvidence("active-release")
    val released = ProjectReadPermitTerminal.Released
    assertEquals(
        ProjectReadPermitEnd.Ended(released, ProjectReadContinuation.Idle),
        release.controller.release(release.permit),
    )
    assertEquals(ProjectReadPermitEnd.AlreadyEnded(released), release.controller.release(release.permit))
    assertTrue(release.controller.admit(release.freshness.capability()) is ProjectReadAdmission.Active)
    observed += "ACTIVE_RELEASE_ENDS_ONCE_TO_IDLE"

    ProjectReadCancellationCause.entries.forEach { cause ->
        val state = activeEvidence("active-cancel-${cause.name}")
        val terminal = ProjectReadPermitTerminal.Cancelled(cause)
        assertEquals(
            ProjectReadPermitEnd.Ended(terminal, ProjectReadContinuation.Idle),
            state.controller.cancel(state.permit, cause),
        )
        assertEquals(
            ProjectReadPermitEnd.AlreadyEnded(terminal),
            state.controller.cancel(state.permit, cause),
        )
        assertTrue(state.controller.admit(state.freshness.capability()) is ProjectReadAdmission.Active)
        observed += "ACTIVE_CANCEL_${cause.name}_ENDS_ONCE_TO_IDLE"
    }

    val fullRelease = fullEvidence("full-release")
    val releasedEnd = fullRelease.controller.release(fullRelease.permit) as ProjectReadPermitEnd.Ended
    assertEquals(ProjectReadPermitTerminal.Released, releasedEnd.terminal)
    val releasedPromotion = releasedEnd.continuation as ProjectReadContinuation.Promoted
    assertSame(fullRelease.request, releasedPromotion.request)
    assertEquals(
        ProjectReadPermitEnd.AlreadyEnded(ProjectReadPermitTerminal.Released),
        fullRelease.controller.release(fullRelease.permit),
    )
    assertEquals(
        QueuedProjectReadCancellation.AlreadyTerminal(
            QueuedProjectReadTerminal.Promoted(releasedPromotion.permit),
        ),
        fullRelease.controller.cancelQueued(
            fullRelease.request,
            ProjectReadCancellationCause.REQUEST_CANCELLED,
        ),
    )
    assertTrue(fullRelease.controller.admit(fullRelease.freshness.capability()) is ProjectReadAdmission.Queued)
    observed += "ACTIVE_AND_QUEUED_RELEASE_ENDS_ONCE_PROMOTES_ONCE_TO_ACTIVE"

    ProjectReadCancellationCause.entries.forEach { cause ->
        val state = fullEvidence("full-cancel-${cause.name}")
        val terminal = ProjectReadPermitTerminal.Cancelled(cause)
        val end = state.controller.cancel(state.permit, cause) as ProjectReadPermitEnd.Ended
        assertEquals(terminal, end.terminal)
        val promotion = end.continuation as ProjectReadContinuation.Promoted
        assertSame(state.request, promotion.request)
        assertEquals(
            ProjectReadPermitEnd.AlreadyEnded(terminal),
            state.controller.cancel(state.permit, cause),
        )
        assertEquals(
            QueuedProjectReadCancellation.AlreadyTerminal(
                QueuedProjectReadTerminal.Promoted(promotion.permit),
            ),
            state.controller.cancelQueued(state.request, cause),
        )
        assertTrue(state.controller.admit(state.freshness.capability()) is ProjectReadAdmission.Queued)
        observed += "ACTIVE_AND_QUEUED_CANCEL_${cause.name}_ENDS_ONCE_PROMOTES_ONCE_TO_ACTIVE"
    }
}

private fun observeQueuedEnds(observed: MutableList<String>) {
    ProjectReadCancellationCause.entries.forEach { cause ->
        val state = fullEvidence("queue-cancel-${cause.name}")
        assertEquals(
            QueuedProjectReadCancellation.Cancelled(cause),
            state.controller.cancelQueued(state.request, cause),
        )
        assertEquals(
            QueuedProjectReadCancellation.AlreadyTerminal(
                QueuedProjectReadTerminal.Cancelled(cause),
            ),
            state.controller.cancelQueued(state.request, cause),
        )
        val replacement = queued(state.controller.admit(state.freshness.capability()))
        val end = state.controller.release(state.permit) as ProjectReadPermitEnd.Ended
        assertSame(replacement, (end.continuation as ProjectReadContinuation.Promoted).request)
        observed += "ACTIVE_AND_QUEUED_CANCEL_QUEUED_${cause.name}_TERMINALIZES_ONCE_TO_ACTIVE"
    }
}

private fun observeRetirements(observed: MutableList<String>) {
    ProjectReadRetirementCause.entries.forEach { cause ->
        val freshness = evidenceFreshness("idle-retire-${cause.name}")
        val controller = controller(freshness.capability())
        assertEquals(
            ProjectReadRetirement.Retired(cause, RetiredProjectReadAuthority.None),
            controller.retire(cause),
        )
        assertRetiredAdmission(controller, freshness, cause)
        observed += "IDLE_RETIRE_${cause.name}_TO_RETIRED"
    }
    ProjectReadRetirementCause.entries.forEach { cause ->
        val state = activeEvidence("active-retire-${cause.name}")
        val retirement = state.controller.retire(cause) as ProjectReadRetirement.Retired
        assertEquals(cause, retirement.cause)
        assertSame(
            state.permit,
            (retirement.authority as RetiredProjectReadAuthority.Active).permit,
        )
        assertEquals(
            ProjectReadPermitEnd.AlreadyEnded(ProjectReadPermitTerminal.Retired(cause)),
            state.controller.release(state.permit),
        )
        assertRetiredAdmission(state.controller, state.freshness, cause)
        observed += "ACTIVE_RETIRE_${cause.name}_TERMINALIZES_ACTIVE_TO_RETIRED"
    }
    ProjectReadRetirementCause.entries.forEach { cause ->
        val state = fullEvidence("full-retire-${cause.name}")
        val retirement = state.controller.retire(cause) as ProjectReadRetirement.Retired
        assertEquals(cause, retirement.cause)
        val authority = retirement.authority as RetiredProjectReadAuthority.ActiveAndQueued
        assertSame(state.permit, authority.permit)
        assertSame(state.request, authority.request)
        assertEquals(
            ProjectReadPermitEnd.AlreadyEnded(ProjectReadPermitTerminal.Retired(cause)),
            state.controller.release(state.permit),
        )
        assertEquals(
            QueuedProjectReadCancellation.AlreadyTerminal(
                QueuedProjectReadTerminal.Retired(cause),
            ),
            state.controller.cancelQueued(
                state.request,
                ProjectReadCancellationCause.REQUEST_CANCELLED,
            ),
        )
        assertRetiredAdmission(state.controller, state.freshness, cause)
        observed += "ACTIVE_AND_QUEUED_RETIRE_${cause.name}_TERMINALIZES_BOTH_TO_RETIRED"
    }
}

private fun observeRetiredTransitions(observed: MutableList<String>) {
    val admissionFreshness = evidenceFreshness("retired-admit")
    val admissionController = controller(admissionFreshness.capability())
    val firstCause = ProjectReadRetirementCause.PROJECT_DISPOSED
    admissionController.retire(firstCause)
    assertRetiredAdmission(admissionController, admissionFreshness, firstCause)
    assertEquals(
        ProjectReadRetirement.AlreadyRetired(firstCause),
        admissionController.retire(ProjectReadRetirementCause.PLUGIN_UNLOADED),
    )
    assertRetiredAdmission(admissionController, admissionFreshness, firstCause)
    observed += "RETIRED_ADMIT_REJECTS_RETAINING_FIRST_CAUSE_NO_MUTATION"

    val retirementFreshness = evidenceFreshness("retired-retire")
    val retirementController = controller(retirementFreshness.capability())
    retirementController.retire(firstCause)
    assertEquals(
        ProjectReadRetirement.AlreadyRetired(firstCause),
        retirementController.retire(ProjectReadRetirementCause.SOCKET_FAILED),
    )
    assertRetiredAdmission(retirementController, retirementFreshness, firstCause)
    observed += "RETIRED_RETIRE_REPEATS_RETAINING_FIRST_CAUSE_NO_MUTATION"
}

private fun assertRetiredAdmission(
    controller: ProjectReadSingleFlight,
    freshness: FreshnessFixture,
    cause: ProjectReadRetirementCause,
) {
    assertEquals(
        ProjectReadAdmission.Rejected(ProjectReadAdmissionFailure.Retired(cause)),
        controller.admit(freshness.capability()),
    )
}

private fun evidenceFreshness(tag: String) =
    FreshnessFixture("/tmp/kast-single-flight-evidence-$tag")

private data class ActiveEvidence(
    val freshness: FreshnessFixture,
    val controller: ProjectReadSingleFlight,
    val permit: ProjectReadPermit,
)

private fun activeEvidence(tag: String): ActiveEvidence {
    val freshness = evidenceFreshness(tag)
    val controller = controller(freshness.capability())
    return ActiveEvidence(freshness, controller, active(controller.admit(freshness.capability())))
}

private data class FullEvidence(
    val freshness: FreshnessFixture,
    val controller: ProjectReadSingleFlight,
    val permit: ProjectReadPermit,
    val request: QueuedProjectReadRequest,
)

private fun fullEvidence(tag: String): FullEvidence {
    val active = activeEvidence(tag)
    return FullEvidence(
        active.freshness,
        active.controller,
        active.permit,
        queued(active.controller.admit(active.freshness.capability())),
    )
}
