package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.UUID

/** Detached native-observation fixture; no filesystem, platform, clock, or semantic admission is needed. */
class WorkspaceReadinessFixture(root: CanonicalWorkspaceRoot, incarnation: UUID = UUID(0, 1)) {
    val identity = WorkspaceModelIdentity(root, IdeReadHostLifetime.fromBoundary(incarnation))
    private var revision = 0
    private val source = ProjectReadEpoch.Source.create { Refinement.Refined(revision) }

    fun ready(): WorkspaceCapabilityReadiness.Ready =
        WorkspaceCapabilityReadiness.Ready(identity, (source.observe() as ProjectReadEpochObservation.Observed).epoch)

    fun advance(): WorkspaceCapabilityReadiness.Ready {
        revision++
        return ready()
    }
}
