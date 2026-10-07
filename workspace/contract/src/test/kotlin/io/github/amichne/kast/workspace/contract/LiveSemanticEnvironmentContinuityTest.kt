package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class LiveSemanticEnvironmentContinuityTest {
    private data class State(val content: Int, val environment: Int)

    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace/root")).value()
    private val owner = LiveSemanticReadOwner(root, IdeReadHostLifetime.fromBoundary(UUID.randomUUID()))
    private var state = State(1, 1)
    private val source =
        ProjectReadEpoch.Source.createWithEnvironment(
            observer = { Refinement.Refined(state) },
            beforeWriteObserver = { Refinement.Refined(state) },
            environment = { it.environment },
        )

    @Test
    fun `content movement permits environment continuity without reviving old authority`() {
        val previous = admit()
        state = state.copy(content = 2)
        val current = admit()
        val proof = current.environmentContinuityFrom(previous).value()
        assertSame(previous, proof.previous)
        assertSame(current, proof.current)
        assertEquals(
            Refinement.Rejected(LiveSemanticReadFailure.EPOCH_MOVED),
            previous.withCurrentOwner { error("stale") },
        )
    }

    @Test
    fun `environment movement cannot produce continuity`() {
        val previous = admit()
        state = state.copy(environment = 2)
        assertEquals(
            Refinement.Rejected(LiveSemanticEnvironmentContinuityFailure.EnvironmentChanged),
            admit().environmentContinuityFrom(previous),
        )
    }

    @Test
    fun `stale target and retired owner reject continuity even with equal environments`() {
        val previous = admit()
        state = state.copy(content = 2)
        val current = admit()
        state = state.copy(content = 3)
        admit()
        assertEquals(
            Refinement.Rejected(
                LiveSemanticEnvironmentContinuityFailure.Authority(LiveSemanticReadFailure.EPOCH_MOVED)
            ),
            current.environmentContinuityFrom(previous),
        )
        owner.retire()
        assertEquals(
            Refinement.Rejected(LiveSemanticEnvironmentContinuityFailure.Authority(LiveSemanticReadFailure.RETIRED)),
            current.environmentContinuityFrom(previous),
        )
    }

    @Test
    fun `unobserved and foreign environments never establish continuity`() {
        val ordinary = LiveReadAuthorityFixture.create(root)
        assertEquals(
            Refinement.Rejected(LiveSemanticEnvironmentContinuityFailure.EnvironmentUnobserved),
            ordinary.environmentContinuityFrom(ordinary),
        )
        assertEquals(
            Refinement.Rejected(LiveSemanticEnvironmentContinuityFailure.Authority(LiveSemanticReadFailure.WRONG_HOST)),
            admit().environmentContinuityFrom(ordinary),
        )
    }

    private fun admit(): LiveSemanticReadAuthority {
        val epoch = (source.observe() as ProjectReadEpochObservation.Observed).epoch
        return owner.admit { VfsPassiveReadAdmission.Admitted(VfsPassiveReadCapability.issue(root, epoch)) }.value()
    }

    private fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Unexpected rejection $failure")
        }
}
