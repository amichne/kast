package io.github.amichne.kast.indexstore.snapshot.evidence

import io.github.amichne.kast.indexstore.snapshot.EvidenceRevision
import io.github.amichne.kast.indexstore.snapshot.PublicationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceIdentity

enum class DurableEvidenceLane {
    SOURCE,
    REFERENCES,
    SEMANTIC_GRAPH,
}

data class DurableEvidenceCandidateIdentity(
    val lane: DurableEvidenceLane,
    val workspace: PublishedWorkspaceIdentity,
    val environment: CandidateEnvironmentFingerprint,
)

sealed interface CandidateCheckpointFailure {
    data object WorkspaceWriteActive : CandidateCheckpointFailure

    data class IdentityMismatch(
        val requested: DurableEvidenceCandidateIdentity,
        val observed: DurableEvidenceCandidateIdentity,
    ) : CandidateCheckpointFailure

    data class ShardConflict(
        val path: CandidateShardPath,
    ) : CandidateCheckpointFailure
}

sealed interface CandidateCheckpointResolution {
    data class Checkpointed(
        val identity: DurableEvidenceCandidateIdentity,
        val shards: List<DurableEvidenceCandidateShard>,
    ) : CandidateCheckpointResolution

    data class Rejected(val failure: CandidateCheckpointFailure) : CandidateCheckpointResolution
}

sealed interface CandidateResumeFailure {
    data class IdentityMismatch(
        val requested: DurableEvidenceCandidateIdentity,
        val observed: DurableEvidenceCandidateIdentity,
    ) : CandidateResumeFailure
}

sealed interface CandidateResumeResolution {
    data object Absent : CandidateResumeResolution

    data class Resumable(
        val identity: DurableEvidenceCandidateIdentity,
        val shards: List<DurableEvidenceCandidateShard>,
    ) : CandidateResumeResolution

    data class Rejected(val failure: CandidateResumeFailure) : CandidateResumeResolution
}

sealed interface CandidateDiscardResolution {
    data object Absent : CandidateDiscardResolution

    data class Discarded(val identity: DurableEvidenceCandidateIdentity) : CandidateDiscardResolution

    data class Rejected(
        val requested: DurableEvidenceCandidateIdentity,
        val observed: DurableEvidenceCandidateIdentity,
    ) : CandidateDiscardResolution

    data object WorkspaceWriteActive : CandidateDiscardResolution
}

data class PublishedEvidenceCandidateSet(
    val identity: DurableEvidenceCandidateIdentity,
    val revision: EvidenceRevision,
    val publishedAt: PublicationEpochMillis,
    val shards: List<DurableEvidenceCandidateShard>,
)

sealed interface PreviousEvidenceLanePublication {
    data object Absent : PreviousEvidenceLanePublication

    data class Retained(val publication: PublishedEvidenceCandidateSet) : PreviousEvidenceLanePublication
}

sealed interface EvidenceLanePublicationState {
    data object Unpublished : EvidenceLanePublicationState

    data class Published(
        val current: PublishedEvidenceCandidateSet,
        val previous: PreviousEvidenceLanePublication,
    ) : EvidenceLanePublicationState
}

sealed interface EvidenceLanePublicationExpectation {
    data object Unpublished : EvidenceLanePublicationExpectation

    data class Published(
        val identity: DurableEvidenceCandidateIdentity,
        val revision: EvidenceRevision,
    ) : EvidenceLanePublicationExpectation
}

sealed interface EvidenceLanePublicationFailure {
    data object WorkspaceWriteActive : EvidenceLanePublicationFailure

    data class CandidateAbsent(val lane: DurableEvidenceLane) : EvidenceLanePublicationFailure

    data class CandidateIdentityMismatch(
        val requested: DurableEvidenceCandidateIdentity,
        val observed: DurableEvidenceCandidateIdentity,
    ) : EvidenceLanePublicationFailure

    data class LaneMoved(
        val expected: EvidenceLanePublicationExpectation,
        val observed: EvidenceLanePublicationState,
    ) : EvidenceLanePublicationFailure
}

sealed interface EvidenceLanePublicationResolution {
    data class Published(
        val publication: EvidenceLanePublicationState.Published,
    ) : EvidenceLanePublicationResolution

    data class Rejected(val failure: EvidenceLanePublicationFailure) : EvidenceLanePublicationResolution
}

internal sealed interface EvidenceLanePublicationCas {
    data object Admitted : EvidenceLanePublicationCas

    data class Rejected(
        val failure: EvidenceLanePublicationFailure.LaneMoved,
    ) : EvidenceLanePublicationCas
}

/**
 * Proof transition: `(EvidenceLanePublicationExpectation, EvidenceLanePublicationState) ->
 * EvidenceLanePublicationCas`.
 *
 * Establishes exact lane identity and revision equality before a pointer can move. Rejection is
 * closed [EvidenceLanePublicationFailure.LaneMoved] data. No raw SQLite value crosses this pure
 * compare-and-set boundary.
 */
internal fun EvidenceLanePublicationExpectation.admit(
    observed: EvidenceLanePublicationState,
): EvidenceLanePublicationCas = when (this) {
    EvidenceLanePublicationExpectation.Unpublished -> when (observed) {
        EvidenceLanePublicationState.Unpublished -> EvidenceLanePublicationCas.Admitted
        is EvidenceLanePublicationState.Published -> EvidenceLanePublicationCas.Rejected(
            EvidenceLanePublicationFailure.LaneMoved(this, observed),
        )
    }
    is EvidenceLanePublicationExpectation.Published -> when (observed) {
        EvidenceLanePublicationState.Unpublished -> EvidenceLanePublicationCas.Rejected(
            EvidenceLanePublicationFailure.LaneMoved(this, observed),
        )
        is EvidenceLanePublicationState.Published ->
            if (identity == observed.current.identity && revision == observed.current.revision) {
                EvidenceLanePublicationCas.Admitted
            } else {
                EvidenceLanePublicationCas.Rejected(
                    EvidenceLanePublicationFailure.LaneMoved(this, observed),
                )
            }
    }
}

sealed interface DurableEvidenceRecordFailure {
    data class InvalidLane(val value: String) : DurableEvidenceRecordFailure

    data object InvalidSetId : DurableEvidenceRecordFailure

    data object BlankWorkspaceIdentity : DurableEvidenceRecordFailure

    data class InvalidEnvironment(val failure: CandidateSha256Failure) : DurableEvidenceRecordFailure

    data class InvalidPath(val failure: CandidateShardPathFailure) : DurableEvidenceRecordFailure

    data class InvalidContentHash(val failure: CandidateSha256Failure) : DurableEvidenceRecordFailure

    data class InvalidStageVersion(val failure: CandidateStageVersionFailure) : DurableEvidenceRecordFailure

    data class InvalidPayload(val failure: CandidateShardPayloadFailure) : DurableEvidenceRecordFailure

    data class InvalidRevision(val value: Long) : DurableEvidenceRecordFailure

    data class InvalidPublicationTime(val value: Long) : DurableEvidenceRecordFailure

}

class InvalidDurableEvidenceRecordException(
    val failure: DurableEvidenceRecordFailure,
) : IllegalStateException(failure.toString())
