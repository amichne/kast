package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.spi.EdtHeartbeatTimeout
import io.github.amichne.kast.workspace.spi.RuntimeLivenessAdmission
import io.github.amichne.kast.workspace.spi.RuntimeLivenessFailure
import io.github.amichne.kast.workspace.spi.SemanticReadFreshness
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively

class IdeaReadAdmissionTest {
    @Test
    fun `frozen heartbeat returns its typed blocker within the local probe budget`() {
        val timeout = EdtHeartbeatTimeout.parse(250).refinedValue()
        val authority = IdeaRuntimeLivenessAuthority(
            runtime = { IdeaRuntimeObservation.Available },
            heartbeat = { IdeaEdtHeartbeatObservation.TimedOut },
            timeout = timeout,
        )

        val admission = assertTimeoutPreemptively(Duration.ofMillis(100)) {
            authority.admit()
        }

        assertEquals(
            RuntimeLivenessAdmission.Rejected(
                RuntimeLivenessFailure.FrozenEventDispatchThread(timeout),
            ),
            admission,
        )
    }

    @Test
    fun `disposed runtime fails without scheduling an EDT heartbeat`() {
        var heartbeatAttempted = false
        val authority = IdeaRuntimeLivenessAuthority(
            runtime = { IdeaRuntimeObservation.Disposed },
            heartbeat = {
                heartbeatAttempted = true
                IdeaEdtHeartbeatObservation.Responded
            },
            timeout = EdtHeartbeatTimeout.parse(250).refinedValue(),
        )

        assertEquals(
            RuntimeLivenessAdmission.Rejected(RuntimeLivenessFailure.RuntimeDisposed),
            authority.admit(),
        )
        assertFalse(heartbeatAttempted)
    }

    @Test
    fun `dumb mode transition and blocked workspace remain distinct freshness states`() {
        assertEquals(
            SemanticReadFreshness.DumbMode,
            freshness(
                dumbMode = IdeaDumbModeObservation.Dumb,
                status = IdeaIndexSemanticAdmission.Status.Pending("transition"),
            ),
        )
        assertEquals(
            SemanticReadFreshness.TransitionInProgress,
            freshness(
                dumbMode = IdeaDumbModeObservation.Smart,
                status = IdeaIndexSemanticAdmission.Status.Pending("transition"),
            ),
        )
        assertEquals(
            SemanticReadFreshness.WorkspaceBlocked,
            freshness(
                dumbMode = IdeaDumbModeObservation.Smart,
                status = IdeaIndexSemanticAdmission.Status.Failed("blocked"),
            ),
        )
    }

    private fun freshness(
        dumbMode: IdeaDumbModeObservation,
        status: IdeaIndexSemanticAdmission.Status,
    ): SemanticReadFreshness = IdeaSemanticReadFreshnessAuthority(
        dumbMode = { dumbMode },
        semanticStatus = { status },
    ).observe()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }
}
