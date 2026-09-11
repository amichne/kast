package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadContentView
import io.github.amichne.kast.workspace.contract.IdeReadEpochRevision
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPreWriteObservation
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HostedPreWriteObservationTest {
    @Test
    fun `guard invokes original owner at most once and rejection also spends it`() {
        for (outcome in listOf(Refinement.Refined(Unit), Refinement.Rejected(HostedQueryFailure.STALE_REQUEST))) {
            var observations = 0
            val guard =
                HostedPreWriteObservation.capture(reference()) {
                    observations++
                    outcome
                }
            assertEquals(outcome, guard.consumeAtWriteBoundary())
            assertEquals(Refinement.Rejected(HostedQueryFailure.STALE_REQUEST), guard.consumeAtWriteBoundary())
            assertEquals(1, observations)
        }
    }

    @Test
    fun `closed guard cannot observe or be revived`() {
        var observations = 0
        val guard =
            HostedPreWriteObservation.capture(reference()) {
                observations++
                Refinement.Refined(Unit)
            }
        guard.close()
        assertEquals(Refinement.Rejected(HostedQueryFailure.STALE_REQUEST), guard.consumeAtWriteBoundary())
        assertEquals(0, observations)
    }

    private fun reference() =
        LiveSemanticReadReference(
            workspaceRoot =
                assertInstanceOf<Refinement.Refined<CanonicalWorkspaceRoot>>(
                        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace"))
                    )
                    .value,
            host = IdeReadHostLifetime.fromBoundary(UUID(0, 1)),
            epoch = assertInstanceOf<Refinement.Refined<IdeReadEpochRevision>>(IdeReadEpochRevision.parse(1)).value,
            contentView = IdeReadContentView.SAVED_PSI_COMMITTED,
            version = LiveSemanticReadReference.VERSION,
        )
}
