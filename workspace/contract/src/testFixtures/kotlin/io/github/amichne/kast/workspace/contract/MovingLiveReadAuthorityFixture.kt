package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.UUID

/** Test-only monotonic observations through the actual original-owner admission transition. */
class MovingLiveReadAuthorityFixture(private val root: CanonicalWorkspaceRoot) {
    private data class Signal(val revision: Long)

    private var signal = Signal(1)
    private val source = ProjectReadEpoch.Source.create { Refinement.Refined(signal) }
    private val owner = LiveSemanticReadOwner(root, IdeReadHostLifetime.fromBoundary(UUID.randomUUID()))

    fun admit(): LiveSemanticReadAuthority {
        val epoch = (source.observe() as ProjectReadEpochObservation.Observed).epoch
        return (owner.admit(VfsPassiveReadCapability.issue(root, epoch)) as Refinement.Refined).value
    }

    fun advance(): LiveSemanticReadAuthority {
        signal = Signal(Math.addExact(signal.revision, 1))
        return admit()
    }
}
