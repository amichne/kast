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

data class HostedTopologyAdapterPorts(
    val candidates: TopologyCandidateEnumerator,
    val extractor: TopologyFileExtractor,
)

enum class HostedIdeRuntimeCompositionFailure {
    TOPOLOGY_STORAGE_UNAVAILABLE,
    MUTATION_STORAGE_UNAVAILABLE,
    MUTATION_RECOVERY_REJECTED,
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
        changePorts: HostedChangeRuntimePorts,
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
        val topology = HostedTopologyComposition.create(
            workspace,
            HostedTopologyRuntimePorts(
                topologyPorts.candidates,
                topologyPorts.extractor,
                snapshots,
            ),
        )
        val selectors = HostedSelectorAuthority.from(reads, workspace, snapshots, snapshots)
        val topologyPublisher = VerifiedTopologyDeltaPublicationService(
            workspace,
            topologyPorts.candidates,
            topologyPorts.extractor,
            snapshots,
        )
        val mutationAdmission = HostedMutationAdmissionOperations {
            HostedMutationComposition.admit(
                workspace,
                topology,
                changePorts,
                journal,
                authority,
                topologyPublisher,
            )
        }
        val mutation = mutationAdmission.admit()
        if (mutation is HostedMutationState.Rejected) {
            return rejected(HostedIdeRuntimeCompositionFailure.MUTATION_RECOVERY_REJECTED)
        }
        val relations = RelationService(workspace, changePorts.relationCompiler)
        val diagnostics = DiagnosticService(workspace, changePorts.diagnosticCompiler)
        return when (val runtime = HostedIdeRuntime.create(
            reads,
            workspace,
            topology,
            selectors,
            relations,
            diagnostics,
            mutation,
            mutationAdmission,
            authority,
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
