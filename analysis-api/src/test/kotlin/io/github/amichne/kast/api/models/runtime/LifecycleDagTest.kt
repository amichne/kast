package io.github.amichne.kast.api.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LifecycleDagTest {
    @Test
    fun sourceBranchesToIndependentReferenceAndGraphCapabilities() {
        val epoch = (RuntimeEpochId.parse("epoch-1") as RuntimeEpochIdResolution.Resolved).epoch
        val revision = (EvidenceRevision.parse(1) as EvidenceRevisionResolution.Resolved).revision
        val admitted = WorkspaceAdmitted(SemanticDemand.source())
        val owned = OwnershipObserved.ExactOwned(admitted, epoch)
        val available = RuntimeAvailable.reused(RevalidatedEpoch(owned))
        val source = SourceReady<RequiredLifecycleCapability.Source>(available.epoch, revision)

        assertEquals(revision, ReferenceReady.committed(source).source.revision)
        assertEquals(revision, GraphReady.committed(source).source.revision)
    }
}
