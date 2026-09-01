package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastSpanCompletion
import io.github.amichne.kast.kernel.KastSpanFailure
import io.github.amichne.kast.kernel.KastSpanName
import io.github.amichne.kast.kernel.KastSpanObservation
import io.github.amichne.kast.kernel.KastTraceSpan
import io.github.amichne.kast.topology.contract.TopologyBuildFailure
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerationFailure
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseGuard
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseUse
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TopologyBuildInstrumentationTest {
    @Test
    fun `workspace rejection records bounded topology terminal state`() = runTest {
        val trace = RecordingObservability()
        val operations = TopologyBuildService.create(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Reconciling },
            MovedLeaseGuard,
            { TopologyCandidateEnumeration.Rejected(
                TopologyCandidateEnumerationFailure.WORKSPACE_UNAVAILABLE,
            ) },
            { request -> TopologyFileExtraction.Failed(
                request.file,
                io.github.amichne.kast.topology.contract.TopologyExtractionFailure.PROJECT_UNAVAILABLE,
            ) },
            RejectingSnapshotStore,
            trace,
        )

        assertEquals(
            TopologyBuildResult.Rejected(TopologyBuildFailure.WorkspaceNotReady),
            operations.build(),
        )
        assertEquals(listOf(KastSpanName.TOPOLOGY_BUILD), trace.names)
        assertEquals(
            KastSpanObservation(
                KastSpanCompletion.Rejected(KastSpanFailure.TOPOLOGY_WORKSPACE_NOT_READY),
            ),
            trace.observations.single(),
        )
    }
}

private class RecordingObservability : KastObservability, KastTraceSpan {
    val names = mutableListOf<KastSpanName>()
    val observations = mutableListOf<KastSpanObservation>()

    override suspend fun <Value> inSpan(
        name: KastSpanName,
        operation: suspend (KastTraceSpan) -> Value,
    ): Value {
        names += name
        return operation(this)
    }

    override suspend fun <Value> child(
        name: KastSpanName,
        operation: suspend (KastTraceSpan) -> Value,
    ): Value = inSpan(name, operation)

    override fun observe(observation: KastSpanObservation) {
        observations += observation
    }
}

private data object RejectingSnapshotStore : TopologySnapshotStore {
    override fun eligible(identity: io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity) =
        TopologySnapshotEligibility.Unavailable

    override fun read(snapshot: io.github.amichne.kast.topology.contract.PublishedTopologySnapshot) =
        TopologySnapshotContentRead.Rejected(
            io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure.STORAGE_UNAVAILABLE,
        )

    override fun publish(
        generation: io.github.amichne.kast.topology.contract.CompleteTopologyGeneration,
    ) = TopologyPublicationResult.Rejected(
        io.github.amichne.kast.topology.contract.TopologyPublicationFailure.STORAGE_UNAVAILABLE,
    )
}

private data object MovedLeaseGuard : SemanticReadLeaseGuard {
    override fun <Value> whileCurrent(
        expected: io.github.amichne.kast.workspace.contract.SemanticReadLease,
        operation: () -> Value,
    ): SemanticReadLeaseUse<Value> = SemanticReadLeaseUse.Moved
}
