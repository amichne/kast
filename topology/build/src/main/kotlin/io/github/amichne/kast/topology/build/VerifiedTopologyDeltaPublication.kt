package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseGuard
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseUse
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import java.nio.file.Path

enum class VerifiedTopologyDeltaPublicationFailure {
    WORKSPACE_MOVED,
    PRIOR_SNAPSHOT_UNAVAILABLE,
    PRIOR_SNAPSHOT_MISMATCH,
    SNAPSHOT_READ_REJECTED,
    CANDIDATE_ENUMERATION_REJECTED,
    CHANGED_SOURCE_MISMATCH,
    CHANGED_SOURCE_EXTRACTION_REJECTED,
    REBIND_REJECTED,
    SOURCE_MOVED,
    PUBLICATION_REJECTED,
}

sealed interface VerifiedTopologyDeltaPublication {
    data class Published(
        val snapshot: PublishedTopologySnapshot,
    ) : VerifiedTopologyDeltaPublication

    data class Unchanged(
        val snapshot: PublishedTopologySnapshot,
    ) : VerifiedTopologyDeltaPublication

    data class Rejected(
        val failure: VerifiedTopologyDeltaPublicationFailure,
    ) : VerifiedTopologyDeltaPublication
}

/** Verification-bound publication of one singleton source delta; it is not a traversal fallback. */
fun interface VerifiedTopologyDeltaPublicationOperations {
    suspend fun publish(
        priorTopologyLease: SemanticReadLease,
        resulting: PublishedWorkspace,
        changedSource: SymbolDiscoveryFileIdentity.Workspace,
        postimage: WorkspaceSourceContentHash,
    ): VerifiedTopologyDeltaPublication
}

/**
 * Preserves a complete durable topology across one verified source write by extracting only the
 * changed file and mechanically rebinding all retained compiler facts to the resulting lease. The
 * prior lease names the durable snapshot admitted by the plan; exact candidate comparison below
 * proves unchanged files across any conservative cold-start publications before application.
 */
class VerifiedTopologyDeltaPublicationService(
    private val leaseGuard: SemanticReadLeaseGuard,
    private val candidates: TopologyCandidateEnumerator,
    private val extractor: TopologyFileExtractor,
    private val snapshots: TopologySnapshotStore,
) : VerifiedTopologyDeltaPublicationOperations {
    override suspend fun publish(
        priorTopologyLease: SemanticReadLease,
        resulting: PublishedWorkspace,
        changedSource: SymbolDiscoveryFileIdentity.Workspace,
        postimage: WorkspaceSourceContentHash,
    ): VerifiedTopologyDeltaPublication {
        val currentIdentity = TopologyWorkspaceIdentity.from(resulting)
        val priorSnapshot = when (
            val guarded = leaseGuard.whileCurrent(resulting.readLease) {
                snapshots.eligible(currentIdentity)
            }
        ) {
            SemanticReadLeaseUse.Moved -> return rejected(
                VerifiedTopologyDeltaPublicationFailure.WORKSPACE_MOVED,
            )
            is SemanticReadLeaseUse.Completed -> when (val eligibility = guarded.value) {
                is TopologySnapshotEligibility.Eligible -> return unchanged(eligibility.snapshot)
                is TopologySnapshotEligibility.Stale -> eligibility.latest
                TopologySnapshotEligibility.Unavailable -> return rejected(
                    VerifiedTopologyDeltaPublicationFailure.PRIOR_SNAPSHOT_UNAVAILABLE,
                )
                is TopologySnapshotEligibility.Rejected -> return rejected(
                    VerifiedTopologyDeltaPublicationFailure.SNAPSHOT_READ_REJECTED,
                )
            }
        }
        if (priorSnapshot.identity.lease != priorTopologyLease) {
            return rejected(VerifiedTopologyDeltaPublicationFailure.PRIOR_SNAPSHOT_MISMATCH)
        }
        val priorContent = when (val read = snapshots.read(priorSnapshot)) {
            is TopologySnapshotContentRead.Loaded -> read.content
            is TopologySnapshotContentRead.Rejected -> return rejected(
                VerifiedTopologyDeltaPublicationFailure.SNAPSHOT_READ_REJECTED,
            )
        }
        val initial = enumerate(resulting) ?: return rejected(
            VerifiedTopologyDeltaPublicationFailure.CANDIDATE_ENUMERATION_REJECTED,
        )
        val changed = initial.files.singleOrNull { candidate ->
            Path.of(resulting.root.value).resolve(candidate.path.value).normalize().toString() ==
                changedSource.path.value
        } ?: return rejected(VerifiedTopologyDeltaPublicationFailure.CHANGED_SOURCE_MISMATCH)
        if (changed.contentHash != postimage) {
            return rejected(VerifiedTopologyDeltaPublicationFailure.CHANGED_SOURCE_MISMATCH)
        }
        val request = when (val admitted = initial.extractionRequest(changed)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(
                VerifiedTopologyDeltaPublicationFailure.CHANGED_SOURCE_MISMATCH,
            )
        }
        val extracted = when (val extraction = extractor.extract(request)) {
            is TopologyFileExtraction.Complete -> extraction.file
            is TopologyFileExtraction.Failed,
            is TopologyFileExtraction.IdentityMismatch,
                -> return rejected(
                VerifiedTopologyDeltaPublicationFailure.CHANGED_SOURCE_EXTRACTION_REJECTED,
            )
        }
        val rebound = when (val reuse = rebindVerifiedSingletonChange(
            resulting,
            initial,
            priorContent,
            extracted,
        )) {
            is VerifiedTopologyGenerationReuse.Rebound -> reuse.generation
            is VerifiedTopologyGenerationReuse.Rejected -> return rejected(
                VerifiedTopologyDeltaPublicationFailure.REBIND_REJECTED,
            )
        }
        val observed = enumerate(resulting) ?: return rejected(
            VerifiedTopologyDeltaPublicationFailure.CANDIDATE_ENUMERATION_REJECTED,
        )
        if (observed.workspace != initial.workspace || observed.files != initial.files) {
            return rejected(VerifiedTopologyDeltaPublicationFailure.SOURCE_MOVED)
        }
        return when (
            val guarded = leaseGuard.whileCurrent(resulting.readLease) {
                snapshots.publish(rebound)
            }
        ) {
            SemanticReadLeaseUse.Moved -> rejected(
                VerifiedTopologyDeltaPublicationFailure.WORKSPACE_MOVED,
            )
            is SemanticReadLeaseUse.Completed -> when (val publication = guarded.value) {
                is TopologyPublicationResult.Published ->
                    VerifiedTopologyDeltaPublication.Published(publication.snapshot)
                is TopologyPublicationResult.Unchanged -> unchanged(publication.snapshot)
                is TopologyPublicationResult.Rejected -> rejected(
                    VerifiedTopologyDeltaPublicationFailure.PUBLICATION_REJECTED,
                )
            }
        }
    }

    private fun enumerate(workspace: PublishedWorkspace): TopologyCandidateSet? = when (
        val enumeration = candidates.enumerate(workspace)
    ) {
        is TopologyCandidateEnumeration.Complete -> enumeration.candidates
        is TopologyCandidateEnumeration.Rejected -> null
    }
}

private fun unchanged(snapshot: PublishedTopologySnapshot): VerifiedTopologyDeltaPublication =
    VerifiedTopologyDeltaPublication.Unchanged(snapshot)

private fun rejected(
    failure: VerifiedTopologyDeltaPublicationFailure,
): VerifiedTopologyDeltaPublication = VerifiedTopologyDeltaPublication.Rejected(failure)
