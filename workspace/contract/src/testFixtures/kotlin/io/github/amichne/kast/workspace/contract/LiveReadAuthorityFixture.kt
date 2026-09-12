package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.UUID

/** Test-only original owner with a fixed epoch; no IDE or production authority escape hatch. */
object LiveReadAuthorityFixture {
    fun create(
        root: CanonicalWorkspaceRoot,
        host: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001"),
    ): LiveSemanticReadAuthority {
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(Unit) }
        val epoch = (source.observe() as ProjectReadEpochObservation.Observed).epoch
        val owner =
            LiveSemanticReadOwner(
                root,
                IdeReadHostLifetime.fromBoundary(host),
            )
        return (owner.admit(VfsPassiveReadCapability.issue(root, epoch)) as Refinement.Refined).value
    }
}
