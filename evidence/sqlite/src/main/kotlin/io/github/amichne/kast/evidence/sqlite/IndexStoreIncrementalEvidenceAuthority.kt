package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.*
import io.github.amichne.kast.evidence.spi.EvidenceCandidateCheckpointAuthority
import io.github.amichne.kast.evidence.spi.PersistedEvidenceLanePublicationAuthority
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.snapshot.EvidenceRevision
import io.github.amichne.kast.indexstore.snapshot.PublicationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceIdentity
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateCheckpointFailure as StoredCheckpointFailure
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateCheckpointResolution as StoredCheckpointResolution
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateContentHash as StoredContentHash
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateDiscardResolution as StoredDiscardResolution
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateEnvironmentFingerprint as StoredEnvironment
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateResumeFailure as StoredResumeFailure
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateResumeResolution as StoredResumeResolution
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateShardPath as StoredShardPath
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateShardPayload as StoredPayload
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateStageVersion as StoredStageVersion
import io.github.amichne.kast.indexstore.snapshot.evidence.DurableEvidenceCandidateBatch as StoredCandidateBatch
import io.github.amichne.kast.indexstore.snapshot.evidence.DurableEvidenceCandidateBatchResolution as StoredBatchResolution
import io.github.amichne.kast.indexstore.snapshot.evidence.DurableEvidenceCandidateIdentity as StoredCandidateIdentity
import io.github.amichne.kast.indexstore.snapshot.evidence.DurableEvidenceCandidateShard as StoredCandidateShard
import io.github.amichne.kast.indexstore.snapshot.evidence.DurableEvidenceLane as StoredEvidenceLane
import io.github.amichne.kast.indexstore.snapshot.evidence.EvidenceLanePublicationExpectation as StoredPublicationExpectation
import io.github.amichne.kast.indexstore.snapshot.evidence.EvidenceLanePublicationFailure as StoredPublicationFailure
import io.github.amichne.kast.indexstore.snapshot.evidence.EvidenceLanePublicationResolution as StoredPublicationResolution
import io.github.amichne.kast.indexstore.snapshot.evidence.EvidenceLanePublicationState as StoredPublicationState
import io.github.amichne.kast.indexstore.snapshot.evidence.EvidenceValueRefinement as StoredRefinement
import io.github.amichne.kast.indexstore.snapshot.evidence.PreviousEvidenceLanePublication as StoredPreviousPublication
import io.github.amichne.kast.indexstore.snapshot.evidence.PublishedEvidenceCandidateSet as StoredPublishedSet
import io.github.amichne.kast.indexstore.store.DurableEvidenceCandidateCheckpointStore
import io.github.amichne.kast.indexstore.store.DurableEvidenceLanePublicationStore
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/**
 * Adapts the canonical source-index SQLite evidence lanes to the host-neutral incremental SPI.
 *
 * Every conversion preserves an already-proven candidate identity, batch bound, atomic lane
 * revision, and current-versus-retained state. No JDBC handle or store transaction escapes.
 */
class IndexStoreIncrementalEvidenceAuthority(
    sourceStore: SqliteSourceIndexStore,
    private val publicationClock: () -> PublicationEpochMillis = {
        PublicationEpochMillis.fromClock(System.currentTimeMillis())
    },
) {
    private val candidateStore: DurableEvidenceCandidateCheckpointStore = sourceStore.durableEvidenceCandidates()
    private val publicationStore: DurableEvidenceLanePublicationStore = sourceStore.durableEvidencePublications()

    val candidates: EvidenceCandidateCheckpointAuthority = CandidateAuthority()
    val publications: PersistedEvidenceLanePublicationAuthority = PublicationAuthority()

    private inner class CandidateAuthority : EvidenceCandidateCheckpointAuthority {
        override fun checkpoint(
            identity: EvidenceCandidateIdentity,
            batch: EvidenceCandidateBatch,
        ): EvidenceCandidateCheckpointResolution = when (
            val resolution = candidateStore.checkpoint(identity.stored(), batch.stored())
        ) {
            is StoredCheckpointResolution.Checkpointed -> EvidenceCandidateCheckpointResolution.Checkpointed(
                resolution.identity.detached(),
                resolution.shards.map(StoredCandidateShard::detached),
            )
            is StoredCheckpointResolution.Rejected -> EvidenceCandidateCheckpointResolution.Rejected(
                when (val failure = resolution.failure) {
                    StoredCheckpointFailure.WorkspaceWriteActive ->
                        EvidenceCandidateCheckpointFailure.WorkspaceWriteActive
                    is StoredCheckpointFailure.IdentityMismatch ->
                        EvidenceCandidateCheckpointFailure.IdentityMismatch(
                            failure.requested.detached(),
                            failure.observed.detached(),
                        )
                    is StoredCheckpointFailure.ShardConflict ->
                        EvidenceCandidateCheckpointFailure.ShardConflict(failure.path.detached())
                },
            )
        }

        override fun resume(identity: EvidenceCandidateIdentity): EvidenceCandidateResumeResolution =
            when (val resolution = candidateStore.resume(identity.stored())) {
                StoredResumeResolution.Absent -> EvidenceCandidateResumeResolution.Absent
                is StoredResumeResolution.Resumable -> EvidenceCandidateResumeResolution.Resumable(
                    resolution.identity.detached(),
                    resolution.shards.map(StoredCandidateShard::detached),
                )
                is StoredResumeResolution.Rejected -> EvidenceCandidateResumeResolution.Rejected(
                    when (val failure = resolution.failure) {
                        is StoredResumeFailure.IdentityMismatch -> EvidenceCandidateResumeFailure.IdentityMismatch(
                            failure.requested.detached(),
                            failure.observed.detached(),
                        )
                    },
                )
            }

        override fun discard(identity: EvidenceCandidateIdentity): EvidenceCandidateDiscardResolution =
            when (val resolution = candidateStore.discard(identity.stored())) {
                StoredDiscardResolution.Absent -> EvidenceCandidateDiscardResolution.Absent
                is StoredDiscardResolution.Discarded -> EvidenceCandidateDiscardResolution.Discarded(
                    resolution.identity.detached(),
                )
                is StoredDiscardResolution.Rejected -> EvidenceCandidateDiscardResolution.Rejected(
                    resolution.requested.detached(),
                    resolution.observed.detached(),
                )
                StoredDiscardResolution.WorkspaceWriteActive ->
                    EvidenceCandidateDiscardResolution.WorkspaceWriteActive
            }
    }

    private inner class PublicationAuthority : PersistedEvidenceLanePublicationAuthority {
        override fun published(lane: PersistedEvidenceLane): PersistedEvidenceLanePublicationState =
            publicationStore.published(lane.stored()).detached()

        override fun publish(
            identity: EvidenceCandidateIdentity,
            expectation: PersistedEvidenceLanePublicationExpectation,
        ): PersistedEvidenceLanePublicationResolution = when (
            val resolution = publicationStore.publish(identity.stored(), expectation.stored(), publicationClock())
        ) {
            is StoredPublicationResolution.Published -> PersistedEvidenceLanePublicationResolution.Published(
                resolution.publication.detached(),
            )
            is StoredPublicationResolution.Rejected -> PersistedEvidenceLanePublicationResolution.Rejected(
                resolution.failure.detached(),
            )
        }
    }
}

private fun PersistedEvidenceLane.stored(): StoredEvidenceLane = when (this) {
    PersistedEvidenceLane.Source -> StoredEvidenceLane.SOURCE
    PersistedEvidenceLane.References -> StoredEvidenceLane.REFERENCES
    PersistedEvidenceLane.SemanticGraph -> StoredEvidenceLane.SEMANTIC_GRAPH
}

private fun StoredEvidenceLane.detached(): PersistedEvidenceLane = when (this) {
    StoredEvidenceLane.SOURCE -> PersistedEvidenceLane.Source
    StoredEvidenceLane.REFERENCES -> PersistedEvidenceLane.References
    StoredEvidenceLane.SEMANTIC_GRAPH -> PersistedEvidenceLane.SemanticGraph
}

/**
 * Proof transition: `EvidenceCandidateIdentity -> DurableEvidenceCandidateIdentity`.
 *
 * Preserves lane, verified workspace identity, and exact environment fingerprint. Rejection in a
 * storage refinement would mean the storage contract became stronger than this contract and is
 * therefore an adapter invariant violation. Raw values remain confined to this adapter.
 */
private fun EvidenceCandidateIdentity.stored(): StoredCandidateIdentity = StoredCandidateIdentity(
    lane.stored(),
    PublishedWorkspaceIdentity(workspace.value),
    StoredEnvironment.refine(environment.value).required(),
)

private fun StoredCandidateIdentity.detached(): EvidenceCandidateIdentity = EvidenceCandidateIdentity(
    lane.detached(),
    WorkspaceStateIdentity(workspace.value),
    EvidenceCandidateEnvironment.refine(environment.value).required(),
)

private fun EvidenceCandidateShard.stored(): StoredCandidateShard = StoredCandidateShard(
    StoredShardPath.refine(path.value).required(),
    StoredContentHash.refine(contentHash.value).required(),
    StoredStageVersion.refine(stageVersion.value).required(),
    StoredPayload.refine(payload.value).required(),
)

private fun StoredCandidateShard.detached(): EvidenceCandidateShard = EvidenceCandidateShard(
    WorkspaceSourcePath.parse(path.value).required(),
    WorkspaceSourceContentHash.parse(contentHash.value).required(),
    EvidenceCandidateStageVersion.refine(stageVersion.value).required(),
    EvidenceCandidatePayload.refine(payload.value).required(),
)

private fun EvidenceCandidateBatch.stored(): StoredCandidateBatch = when (
    val resolution = StoredCandidateBatch.refine(shards.map(EvidenceCandidateShard::stored))
) {
    is StoredBatchResolution.Resolved -> resolution.batch
    is StoredBatchResolution.Rejected -> error("Evidence batch violated storage bounds: ${resolution.failure}")
}

private fun EvidenceLaneRevision.stored(): EvidenceRevision =
    EvidenceRevision.fromSourceIndexGeneration(SourceIndexGeneration(value))

private fun EvidenceRevision.detached(): EvidenceLaneRevision = EvidenceLaneRevision.parse(value).required()

private fun PersistedEvidenceLanePublicationExpectation.stored(): StoredPublicationExpectation = when (this) {
    PersistedEvidenceLanePublicationExpectation.Unpublished -> StoredPublicationExpectation.Unpublished
    is PersistedEvidenceLanePublicationExpectation.Published -> StoredPublicationExpectation.Published(
        identity.stored(),
        revision.stored(),
    )
}

private fun StoredPublicationExpectation.detached(): PersistedEvidenceLanePublicationExpectation = when (this) {
    StoredPublicationExpectation.Unpublished -> PersistedEvidenceLanePublicationExpectation.Unpublished
    is StoredPublicationExpectation.Published -> PersistedEvidenceLanePublicationExpectation.Published(
        identity.detached(),
        revision.detached(),
    )
}

private fun StoredPublishedSet.detached(): PublishedPersistedEvidenceSet = PublishedPersistedEvidenceSet(
    identity.detached(),
    revision.detached(),
    shards.map(StoredCandidateShard::detached),
)

private fun StoredPublicationState.detached(): PersistedEvidenceLanePublicationState = when (this) {
    StoredPublicationState.Unpublished -> PersistedEvidenceLanePublicationState.Unpublished
    is StoredPublicationState.Published -> detached()
}

private fun StoredPublicationState.Published.detached(): PersistedEvidenceLanePublicationState.Published =
    PersistedEvidenceLanePublicationState.Published(
        current.detached(),
        when (val retained = previous) {
            StoredPreviousPublication.Absent -> PreviousPersistedEvidencePublication.Absent
            is StoredPreviousPublication.Retained -> PreviousPersistedEvidencePublication.Retained(
                retained.publication.detached(),
            )
        },
    )

private fun StoredPublicationFailure.detached(): PersistedEvidenceLanePublicationFailure = when (this) {
    StoredPublicationFailure.WorkspaceWriteActive ->
        PersistedEvidenceLanePublicationFailure.WorkspaceWriteActive
    is StoredPublicationFailure.CandidateAbsent -> PersistedEvidenceLanePublicationFailure.CandidateAbsent(
        lane.detached(),
    )
    is StoredPublicationFailure.CandidateIdentityMismatch ->
        PersistedEvidenceLanePublicationFailure.CandidateIdentityMismatch(
            requested.detached(),
            observed.detached(),
        )
    is StoredPublicationFailure.LaneMoved -> PersistedEvidenceLanePublicationFailure.LaneMoved(
        expected.detached(),
        observed.detached(),
    )
}

private fun StoredShardPath.detached(): WorkspaceSourcePath = WorkspaceSourcePath.parse(value).required()

private fun <Value, Failure> Refinement<Value, Failure>.required(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("Detached evidence proof was rejected: $failure")
}

private fun <Value, Failure> StoredRefinement<Value, Failure>.required(): Value = when (this) {
    is StoredRefinement.Refined -> value
    is StoredRefinement.Rejected -> error("Stored evidence proof was rejected: $failure")
}
