package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.sqlite.SqliteDurableChangeAuthority
import io.github.amichne.kast.evidence.sqlite.SqliteHostedMutationAuthorityOpenResult
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStore
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStoreOpening
import io.github.amichne.kast.evidence.sqlite.SqliteHostedWorkspaceGenerationAuthority
import io.github.amichne.kast.evidence.sqlite.HostedWorkspaceGenerationIssuance
import io.github.amichne.kast.evidence.sqlite.HostedWorkspaceGenerationResumption
import io.github.amichne.kast.diagnostic.service.DiagnosticService
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.topology.build.VerifiedTopologyDeltaPublicationService
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.change.apply.AppliedIndexSynchronizationTask
import io.github.amichne.kast.change.apply.CoalescingAppliedIndexSynchronizationScheduler
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshOperations
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointTelemetryOutput
import io.github.amichne.kast.runtime.telemetry.OpenTelemetryFileForwarding
import io.github.amichne.kast.runtime.telemetry.OpenTelemetryFileForwardingOpening
import io.github.amichne.kast.workspace.service.WorkspaceIndexPublicationOperations
import io.github.amichne.kast.workspace.service.WorkspaceIndexSynchronizationService
import java.util.concurrent.Executor

data class HostedTopologyAdapterPorts(
    val candidates: TopologyCandidateEnumerator,
    val extractor: TopologyFileExtractor,
)

data class HostedIndexRuntimePorts(
    val refresh: WorkspaceIndexRefreshOperations,
    val asynchronousExecutor: Executor,
)

data class HostedObservabilityRuntimePorts(
    val output: IdeEndpointTelemetryOutput,
)

enum class HostedIdeRuntimeCompositionFailure {
    TOPOLOGY_STORAGE_UNAVAILABLE,
    MUTATION_STORAGE_UNAVAILABLE,
    MUTATION_RECOVERY_REJECTED,
    TELEMETRY_UNAVAILABLE,
    INCOMPLETE_BINDING_TABLE,
}

sealed interface HostedIdeRuntimeCompositionResult {
    data class Created(val runtime: HostedIdeRuntime) : HostedIdeRuntimeCompositionResult
    data class Rejected(val failure: HostedIdeRuntimeCompositionFailure) :
        HostedIdeRuntimeCompositionResult
}

sealed interface HostedSemanticGenerationIssuance {
    data class Issued(val generation: EvidenceGeneration) : HostedSemanticGenerationIssuance
    data object Rejected : HostedSemanticGenerationIssuance
}

sealed interface HostedSemanticGenerationResumption {
    data class Resumed(
        val sourceState: WorkspaceStateIdentity,
        val generation: EvidenceGeneration,
    ) : HostedSemanticGenerationResumption

    data object Rejected : HostedSemanticGenerationResumption
}

/** Opens durable adapters and publishes one runtime only after every hosted effect is admitted. */
object HostedIdeRuntimeComposition {
    fun resumeSemanticGeneration(
        location: HostedWorkspaceStateLocation,
        basis: WorkspaceStateIdentity,
    ): HostedSemanticGenerationResumption = when (
        val resumption = SqliteHostedWorkspaceGenerationAuthority.resume(
            location.mutationDatabase,
            basis,
        )
    ) {
        is HostedWorkspaceGenerationResumption.Resumed ->
            HostedSemanticGenerationResumption.Resumed(
                resumption.sourceState,
                resumption.generation,
            )
        is HostedWorkspaceGenerationResumption.Rejected ->
            HostedSemanticGenerationResumption.Rejected
    }

    fun advanceSemanticGeneration(
        location: HostedWorkspaceStateLocation,
        prior: WorkspaceStateIdentity,
        next: WorkspaceStateIdentity,
    ): HostedSemanticGenerationIssuance = when (
        val issuance = SqliteHostedWorkspaceGenerationAuthority.advance(
            location.mutationDatabase,
            prior,
            next,
        )
    ) {
        is HostedWorkspaceGenerationIssuance.Issued -> HostedSemanticGenerationIssuance.Issued(
            issuance.generation,
        )
        is HostedWorkspaceGenerationIssuance.Rejected -> HostedSemanticGenerationIssuance.Rejected
    }

    fun create(
        reads: HostedReadRuntimeOperations,
        workspace: HostedWorkspaceOperations,
        location: HostedWorkspaceStateLocation,
        topologyPorts: HostedTopologyAdapterPorts,
        indexPorts: HostedIndexRuntimePorts,
        changePorts: HostedChangeRuntimePorts,
        observabilityPorts: HostedObservabilityRuntimePorts,
    ): HostedIdeRuntimeCompositionResult {
        val snapshots = when (val opened = SqliteTopologySnapshotStore.open(
            location.topologyDatabase,
        )) {
            is SqliteTopologySnapshotStoreOpening.Opened -> opened.store
            is SqliteTopologySnapshotStoreOpening.Rejected -> return rejected(
                HostedIdeRuntimeCompositionFailure.TOPOLOGY_STORAGE_UNAVAILABLE,
            )
        }
        val mutationAuthority = when (val opened = SqliteDurableChangeAuthority.openHosted(
            location.mutationDatabase,
        )) {
            is SqliteHostedMutationAuthorityOpenResult.Opened -> opened
            is SqliteHostedMutationAuthorityOpenResult.Rejected -> return rejected(
                HostedIdeRuntimeCompositionFailure.MUTATION_STORAGE_UNAVAILABLE,
            )
        }
        val journal = mutationAuthority.recoveryJournal
        val authority = mutationAuthority.authority
        val observability = when (val opened = OpenTelemetryFileForwarding.open(
            observabilityPorts.output,
        )) {
            is OpenTelemetryFileForwardingOpening.Opened -> opened.forwarding.observability
            is OpenTelemetryFileForwardingOpening.Rejected -> return rejected(
                HostedIdeRuntimeCompositionFailure.TELEMETRY_UNAVAILABLE,
            )
        }
        val topology = HostedTopologyComposition.create(
            workspace,
            HostedTopologyRuntimePorts(
                topologyPorts.candidates,
                topologyPorts.extractor,
                snapshots,
            ),
            observability,
        )
        val selectors = HostedSelectorAuthority.from(reads, workspace, snapshots, snapshots)
        val topologyPublisher = VerifiedTopologyDeltaPublicationService(
            workspace,
            topologyPorts.candidates,
            topologyPorts.extractor,
            snapshots,
        )
        val indexSync = WorkspaceIndexSynchronizationService(
            workspace,
            indexPorts.refresh,
            WorkspaceIndexPublicationOperations(workspace::publishAfterIndexRefresh),
        )
        val indexScheduler = CoalescingAppliedIndexSynchronizationScheduler(
            indexPorts.asynchronousExecutor,
            AppliedIndexSynchronizationTask { indexSync.synchronize() },
        )
        val mutationAdmission = HostedMutationAdmissionOperations {
            HostedMutationComposition.admit(
                workspace,
                topology,
                changePorts,
                journal,
                authority,
                topologyPublisher,
                indexScheduler,
            )
        }
        val mutation = mutationAdmission.admit()
        if (mutation is HostedMutationState.Rejected) {
            return rejected(HostedIdeRuntimeCompositionFailure.MUTATION_RECOVERY_REJECTED)
        }
        val relations = RelationService(workspace, changePorts.relationCompiler, observability)
        val diagnostics = DiagnosticService(workspace, changePorts.diagnosticCompiler)
        return when (val runtime = HostedIdeRuntime.create(
            reads,
            workspace,
            topology,
            selectors,
            relations,
            diagnostics,
            indexSync,
            mutation,
            mutationAdmission,
            authority,
            observability,
        )) {
            is HostedIdeRuntimeConstruction.Created -> HostedIdeRuntimeCompositionResult.Created(
                runtime.runtime,
            )
            is HostedIdeRuntimeConstruction.Rejected -> rejected(
                HostedIdeRuntimeCompositionFailure.INCOMPLETE_BINDING_TABLE,
            )
        }
    }

    private fun rejected(failure: HostedIdeRuntimeCompositionFailure) =
        HostedIdeRuntimeCompositionResult.Rejected(failure)
}
