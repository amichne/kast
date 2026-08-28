package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.sqlite.SqliteDurableChangeAuthority
import io.github.amichne.kast.evidence.sqlite.SqliteDurableChangeAuthorityOpenResult
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournal
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournalOpenResult
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStore
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStoreOpening
import io.github.amichne.kast.evidence.sqlite.SqliteHostedWorkspaceGenerationAuthority
import io.github.amichne.kast.evidence.sqlite.HostedWorkspaceGenerationIssuance
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntime
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

/** Opens durable adapters and publishes one runtime only after every hosted effect is admitted. */
object HostedIdeRuntimeComposition {
    fun issueSemanticGeneration(
        location: HostedWorkspaceStateLocation,
        state: WorkspaceStateIdentity,
    ): HostedSemanticGenerationIssuance = when (
        val issuance = SqliteHostedWorkspaceGenerationAuthority.issue(
            location.mutationDatabase,
            state,
        )
    ) {
        is HostedWorkspaceGenerationIssuance.Issued -> HostedSemanticGenerationIssuance.Issued(
            issuance.generation,
        )
        is HostedWorkspaceGenerationIssuance.Rejected -> HostedSemanticGenerationIssuance.Rejected
    }

    fun create(
        reads: HostedIdeReadRuntime,
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
        val journal = when (val opened = SqliteMutationRecoveryJournal.open(
            location.mutationDatabase,
        )) {
            is SqliteMutationRecoveryJournalOpenResult.Opened -> opened.journal
            is SqliteMutationRecoveryJournalOpenResult.Rejected -> return rejected(
                HostedIdeRuntimeCompositionFailure.MUTATION_STORAGE_UNAVAILABLE,
            )
        }
        val authority = when (val opened = SqliteDurableChangeAuthority.open(
            location.mutationDatabase,
        )) {
            is SqliteDurableChangeAuthorityOpenResult.Opened -> opened.authority
            is SqliteDurableChangeAuthorityOpenResult.Rejected -> return rejected(
                HostedIdeRuntimeCompositionFailure.MUTATION_STORAGE_UNAVAILABLE,
            )
        }
        val topology = HostedTopologyComposition.create(
            workspace,
            HostedTopologyRuntimePorts(
                topologyPorts.candidates,
                topologyPorts.extractor,
                snapshots,
            ),
        )
        val selectors = HostedSelectorAuthority.from(reads, workspace, snapshots, snapshots)
        val mutation = HostedMutationComposition.admit(
            workspace,
            topology,
            changePorts,
            journal,
            authority,
            VerifiedTopologyDeltaPublicationService(
                workspace,
                topologyPorts.candidates,
                topologyPorts.extractor,
                snapshots,
            ),
        )
        if (mutation is HostedMutationState.Rejected) {
            return rejected(HostedIdeRuntimeCompositionFailure.MUTATION_RECOVERY_REJECTED)
        }
        return when (val runtime = HostedIdeRuntime.create(
            reads,
            topology,
            selectors,
            mutation,
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
