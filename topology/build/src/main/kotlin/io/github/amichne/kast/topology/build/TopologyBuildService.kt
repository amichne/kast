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
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
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
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath

/** Private capability whose construction and use exist only inside `:topology:build`. */
private class TopologyBuildAuthority {
    suspend fun execute(operation: suspend () -> TopologyBuildResult): TopologyBuildResult =
        operation()
}

private class ExactDurableTopologySnapshot private constructor(
    val snapshot: PublishedTopologySnapshot,
) {
    companion object {
        /**
         * Proof transition: `(TopologyWorkspaceIdentity, PublishedTopologySnapshot) ->
         * Refinement<ExactDurableTopologySnapshot,
         * TopologyBuildFailure.SnapshotContractViolation>`.
         *
         * Establishes that the durable eligibility adapter returned the exact requested workspace
         * identity. The closed expected failure is
         * [TopologyBuildFailure.SnapshotContractViolation]. Raw adapter output is consumed only at
         * this durable reuse boundary.
         */
        fun validate(
            identity: TopologyWorkspaceIdentity,
            snapshot: PublishedTopologySnapshot,
        ): Refinement<
            ExactDurableTopologySnapshot,
            TopologyBuildFailure.SnapshotContractViolation,
        > = if (snapshot.identity == identity) {
            Refinement.Refined(ExactDurableTopologySnapshot(snapshot))
        } else {
            Refinement.Rejected(TopologyBuildFailure.SnapshotContractViolation)
        }
    }
}

private sealed interface TopologyGenerationRevalidationFailure {
    data class SourceEvidenceMoved(
        val path: WorkspaceSourcePath,
    ) : TopologyGenerationRevalidationFailure
    data object WorkspaceMismatch : TopologyGenerationRevalidationFailure
    data object GenerationMismatch : TopologyGenerationRevalidationFailure
}

private class RevalidatedTopologyGeneration private constructor(
    val generation: CompleteTopologyGeneration,
) {
    companion object {
        /**
         * Proof transition: `(TopologyCandidateSet, TopologyCandidateSet,
         * CompleteTopologyGeneration) -> Refinement<RevalidatedTopologyGeneration,
         * TopologyGenerationRevalidationFailure>`.
         *
         * Establishes that the exact candidate paths, ownership, and content hashes did not move
         * during extraction and that the generation covers that stable evidence. The closed
         * expected failure is [TopologyGenerationRevalidationFailure]. Fresh physical evidence
         * may enter only through the injected candidate enumerator.
         */
        fun validate(
            original: TopologyCandidateSet,
            observed: TopologyCandidateSet,
            generation: CompleteTopologyGeneration,
        ): Refinement<
            RevalidatedTopologyGeneration,
            TopologyGenerationRevalidationFailure,
        > {
            if (original.workspace != observed.workspace) {
                return Refinement.Rejected(
                    TopologyGenerationRevalidationFailure.WorkspaceMismatch,
                )
            }
            val originalFiles = original.files.associateBy { it.path }
            val observedFiles = observed.files.associateBy { it.path }
            val movedPath = (originalFiles.keys + observedFiles.keys)
                .sortedBy(WorkspaceSourcePath::value)
                .firstOrNull { originalFiles[it] != observedFiles[it] }
            if (movedPath != null) {
                return Refinement.Rejected(
                    TopologyGenerationRevalidationFailure.SourceEvidenceMoved(movedPath),
                )
            }
            if (
                generation.identity != original.workspace ||
                generation.files.map(CompleteTopologyFile::file) != original.files
            ) {
                return Refinement.Rejected(
                    TopologyGenerationRevalidationFailure.GenerationMismatch,
                )
            }
            return Refinement.Refined(RevalidatedTopologyGeneration(generation))
        }
    }
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
        val eligibility = when (
            val guarded = leaseGuard.whileCurrent(workspace.readLease) {
                snapshots.eligible(identity)
            }
        ) {
            SemanticReadLeaseUse.Moved -> return TopologyBuildResult.WorkspaceMoved
            is SemanticReadLeaseUse.Completed -> guarded.value
        }
        val prior = when (val existing = eligibility) {
            is TopologySnapshotEligibility.Eligible -> {
                val durable = when (val validation = ExactDurableTopologySnapshot.validate(
                    identity,
                    existing.snapshot,
                )) {
                    is Refinement.Refined -> validation.value
                    is Refinement.Rejected -> return rejected(validation.failure)
                }
                return TopologyBuildResult.Reused(durable.snapshot)
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
                        candidateSet,
                        reuse.generation,
                        TopologyPublicationMode.REBOUND,
                    )
                    TopologyGenerationReuse.Rejected ->
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
                is TopologyFileExtraction.Complete -> completed += extraction.file
                is TopologyFileExtraction.Failed -> {
                    if (extraction.file !in candidateSet.files) {
                        return rejected(TopologyBuildFailure.ExtractionContractViolation)
                    }
                    return rejected(
                        TopologyBuildFailure.Extraction(
                            extraction.file.path,
                            extraction.failure,
                        ),
                    )
                }
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
            candidateSet,
            generation,
            TopologyPublicationMode.EXTRACTED,
        )
    }

    private fun publishCompleteGeneration(
        workspace: PublishedWorkspace,
        originalCandidates: TopologyCandidateSet,
        generation: CompleteTopologyGeneration,
        mode: TopologyPublicationMode,
    ): TopologyBuildResult {
        val observedCandidates = when (val enumeration = candidates.enumerate(workspace)) {
            is TopologyCandidateEnumeration.Complete -> enumeration.candidates
            is TopologyCandidateEnumeration.Rejected ->
                return rejected(TopologyBuildFailure.Enumeration(enumeration.failure))
        }
        val stableGeneration = when (
            val validation = RevalidatedTopologyGeneration.validate(
                originalCandidates,
                observedCandidates,
                generation,
            )
        ) {
            is Refinement.Refined -> validation.value
            is Refinement.Rejected -> return when (val failure = validation.failure) {
                is TopologyGenerationRevalidationFailure.SourceEvidenceMoved -> rejected(
                    TopologyBuildFailure.Extraction(
                        failure.path,
                        TopologyExtractionFailure.SOURCE_CONTENT_CHANGED_DURING_BUILD,
                    ),
                )
                TopologyGenerationRevalidationFailure.WorkspaceMismatch,
                TopologyGenerationRevalidationFailure.GenerationMismatch ->
                    rejected(TopologyBuildFailure.ExtractionContractViolation)
            }
        }
        return when (
            val guarded = leaseGuard.whileCurrent(workspace.readLease) {
                snapshots.publish(stableGeneration.generation)
            }
        ) {
            SemanticReadLeaseUse.Moved -> TopologyBuildResult.WorkspaceMoved
            is SemanticReadLeaseUse.Completed -> when (val publication = guarded.value) {
                is TopologyPublicationResult.Published -> {
                    when (mode) {
                        TopologyPublicationMode.EXTRACTED ->
                            TopologyBuildResult.Published(publication.snapshot)
                        TopologyPublicationMode.REBOUND ->
                            TopologyBuildResult.Reused(publication.snapshot)
                    }
                }
                is TopologyPublicationResult.Unchanged ->
                    TopologyBuildResult.Reused(publication.snapshot)
                is TopologyPublicationResult.Rejected -> rejected(
                    TopologyBuildFailure.Publication(publication.failure),
                )
            }
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
