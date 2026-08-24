package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/** Exact workspace publication identity retained by topology evidence. */
data class TopologyWorkspaceIdentity(
    val lease: SemanticReadLease,
    val sourceState: WorkspaceStateIdentity,
) {
    companion object {
        /**
         * Proof transition: `PublishedWorkspace -> TopologyWorkspaceIdentity`.
         *
         * Preserves the workspace's canonical root, evidence generation, and semantic source
         * identity as one detached topology identity. Raw identity extraction is permitted only
         * by the topology persistence adapter.
         */
        fun from(workspace: PublishedWorkspace): TopologyWorkspaceIdentity =
            TopologyWorkspaceIdentity(workspace.readLease, workspace.sourceState)
    }

}
