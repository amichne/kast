package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyBuildFailure
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseGuard
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseUse
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import java.util.concurrent.atomic.AtomicReference

/** Private capability whose construction and use exist only inside `:topology:build`. */
private class TopologyBuildAuthority {
    private val retained = AtomicReference<RetainedTopologySnapshot>(
        RetainedTopologySnapshot.Unavailable,
    )

    suspend fun execute(operation: suspend () -> TopologyBuildResult): TopologyBuildResult =
        operation()

    /**
     * Proof transition: `TopologyWorkspaceIdentity -> RetainedTopologySnapshot`.
     *
     * Exact establishes an already-published snapshot for the requested identity; Unavailable is
     * the closed absence state. The retained publication proof never leaves this private build
     * authority.
     */
    fun recall(identity: TopologyWorkspaceIdentity): RetainedTopologySnapshot =
        when (val current = retained.get()) {
            is RetainedTopologySnapshot.Exact -> if (current.snapshot.identity == identity) {
                current
            } else {
                RetainedTopologySnapshot.Unavailable
            }
            RetainedTopologySnapshot.Unavailable -> RetainedTopologySnapshot.Unavailable
        }

    /** Preserves one exact publication proof for a later build with the same identity. */
    fun retain(snapshot: PublishedTopologySnapshot) {
        retained.set(RetainedTopologySnapshot.Exact(snapshot))
    }
}

private sealed interface RetainedTopologySnapshot {
    data object Unavailable : RetainedTopologySnapshot
    data class Exact(val snapshot: PublishedTopologySnapshot) : RetainedTopologySnapshot
}

/** Explicit coordinator that alone can turn terminal K2 coverage into publication. */
class TopologyBuildService private constructor(
    private val workspaces: WorkspaceInspectionOperations,
    private val leaseGuard: SemanticReadLeaseGuard,
    private val candidates: TopologyCandidateEnumerator,
    private val extractor: TopologyFileExtractor,
    private val snapshots: TopologySnapshotStore,
    private val authority: TopologyBuildAuthority,
) : TopologyBuildOperations {
    override suspend fun build(): TopologyBuildResult = authority.execute(::buildAuthorized)

    private suspend fun buildAuthorized(): TopologyBuildResult {
        val workspace = when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
                -> return rejected(TopologyBuildFailure.WorkspaceNotReady)
        }
        val identity = TopologyWorkspaceIdentity.from(workspace)
        when (val retained = authority.recall(identity)) {
            is RetainedTopologySnapshot.Exact ->
                return TopologyBuildResult.Reused(retained.snapshot)
            RetainedTopologySnapshot.Unavailable -> Unit
        }
        val prior = when (val existing = snapshots.eligible(identity)) {
            is TopologySnapshotEligibility.Eligible ->
                return if (existing.snapshot.identity == identity) {
                    authority.retain(existing.snapshot)
                    TopologyBuildResult.Reused(existing.snapshot)
                } else {
                    rejected(TopologyBuildFailure.SnapshotContractViolation)
                }
            is TopologySnapshotEligibility.Rejected ->
                return rejected(TopologyBuildFailure.SnapshotRead(existing.failure))
            is TopologySnapshotEligibility.Stale ->
                PriorTopologySnapshot.Stale(existing.latest)
            TopologySnapshotEligibility.Unavailable -> PriorTopologySnapshot.Unavailable
        }
        val candidateSet = when (val enumeration = candidates.enumerate(workspace)) {
            is TopologyCandidateEnumeration.Complete -> enumeration.candidates
            is TopologyCandidateEnumeration.Rejected ->
                return rejected(TopologyBuildFailure.Enumeration(enumeration.failure))
        }
        if (candidateSet.workspace != identity) {
            return rejected(TopologyBuildFailure.ExtractionContractViolation)
        }
        when (prior) {
            is PriorTopologySnapshot.Stale -> {
                val content = when (val read = snapshots.read(prior.snapshot)) {
                    is TopologySnapshotContentRead.Loaded -> read.content
                    is TopologySnapshotContentRead.Rejected ->
                        return rejected(TopologyBuildFailure.SnapshotRead(read.failure))
                }
                when (
                    val reuse = rebindUnchangedTopologyGeneration(workspace, candidateSet, content)
                ) {
                    is TopologyGenerationReuse.Rebound -> return publishCompleteGeneration(
                        workspace,
                        reuse.generation,
                        TopologyPublicationMode.REBOUND,
                    )
                    is TopologyGenerationReuse.Rejected ->
                        return rejected(TopologyBuildFailure.SnapshotContractViolation)
                    TopologyGenerationReuse.SourceChanged -> Unit
                }
            }
            PriorTopologySnapshot.Unavailable -> Unit
        }
        val completed = mutableListOf<CompleteTopologyFile>()
        for (file in candidateSet.files) {
            val request = when (val admitted = candidateSet.extractionRequest(file)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return rejected(TopologyBuildFailure.ExtractionContractViolation)
            }
            when (val extraction = extractor.extract(request)) {
                is TopologyFileExtraction.Complete -> {
                    if (extraction.file.file != file) {
                        return rejected(TopologyBuildFailure.ExtractionContractViolation)
                    }
                    completed += extraction.file
                }
                is TopologyFileExtraction.Failed -> return rejected(
                    TopologyBuildFailure.Extraction(file.path, extraction.failure),
                )
            }
        }
        val generation = when (
            val admitted = CompleteTopologyGeneration.admit(
                workspace,
                candidateSet.files,
                completed,
            )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(
                TopologyBuildFailure.Coverage(admitted.failure),
            )
        }
        return publishCompleteGeneration(
            workspace,
            generation,
            TopologyPublicationMode.EXTRACTED,
        )
    }

    private fun publishCompleteGeneration(
        workspace: PublishedWorkspace,
        generation: CompleteTopologyGeneration,
        mode: TopologyPublicationMode,
    ): TopologyBuildResult = when (
        val guarded = leaseGuard.whileCurrent(workspace.readLease) {
            snapshots.publish(generation)
        }
    ) {
        SemanticReadLeaseUse.Moved -> TopologyBuildResult.WorkspaceMoved
        is SemanticReadLeaseUse.Completed -> when (val publication = guarded.value) {
            is TopologyPublicationResult.Published -> {
                authority.retain(publication.snapshot)
                when (mode) {
                    TopologyPublicationMode.EXTRACTED ->
                        TopologyBuildResult.Published(publication.snapshot)
                    TopologyPublicationMode.REBOUND ->
                        TopologyBuildResult.Reused(publication.snapshot)
                }
            }
            is TopologyPublicationResult.Unchanged -> {
                authority.retain(publication.snapshot)
                TopologyBuildResult.Reused(publication.snapshot)
            }
            is TopologyPublicationResult.Rejected -> rejected(
                TopologyBuildFailure.Publication(publication.failure),
            )
        }
    }

    companion object {
        /**
         * Proof transition: `(workspace observation, current-lease guard, admitted-root
         * enumerator, K2 extractor, snapshot reader and publisher) -> TopologyBuildOperations`.
         *
         * Establishes the sole explicit build service carrying private [TopologyBuildAuthority].
         * Platform and persistence effects remain behind their injected ports. Raw construction is
         * permitted only in runtime composition while binding `topology.build`.
         */
        fun create(
            workspaces: WorkspaceInspectionOperations,
            leaseGuard: SemanticReadLeaseGuard,
            candidates: TopologyCandidateEnumerator,
            extractor: TopologyFileExtractor,
            snapshots: TopologySnapshotStore,
        ): TopologyBuildOperations = TopologyBuildService(
            workspaces,
            leaseGuard,
            candidates,
            extractor,
            snapshots,
            TopologyBuildAuthority(),
        )
    }
}

private sealed interface PriorTopologySnapshot {
    data class Stale(val snapshot: PublishedTopologySnapshot) : PriorTopologySnapshot
    data object Unavailable : PriorTopologySnapshot
}

private enum class TopologyPublicationMode { EXTRACTED, REBOUND }

private fun rejected(failure: TopologyBuildFailure): TopologyBuildResult.Rejected =
    TopologyBuildResult.Rejected(failure)
