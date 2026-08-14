package io.github.amichne.kast.evidence.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

enum class PersistedEvidenceLane {
    Source,
    References,
    SemanticGraph,
}

data class EvidenceCandidateIdentity(
    val lane: PersistedEvidenceLane,
    val workspace: WorkspaceStateIdentity,
    val environment: EvidenceCandidateEnvironment,
)

enum class EvidenceLaneRevisionFailure {
    NOT_POSITIVE,
}

/** Positive, independently monotonic revision of one persisted evidence lane. */
@JvmInline
value class EvidenceLaneRevision private constructor(val value: Long) {
    companion object {
        fun first(): EvidenceLaneRevision = EvidenceLaneRevision(1)

        /**
         * Proof transition: `Long -> Refinement<EvidenceLaneRevision,
         * EvidenceLaneRevisionFailure>`.
         *
         * Establishes a positive persisted lane revision. [EvidenceLaneRevisionFailure] is the
         * closed expected failure. Raw numbers may enter only at persistence or protocol
         * boundaries.
         */
        fun parse(raw: Long): Refinement<EvidenceLaneRevision, EvidenceLaneRevisionFailure> =
            if (raw > 0) Refinement.Refined(EvidenceLaneRevision(raw))
            else Refinement.Rejected(EvidenceLaneRevisionFailure.NOT_POSITIVE)
    }
}

sealed interface EvidenceCandidateCheckpointFailure {
    data object WorkspaceWriteActive : EvidenceCandidateCheckpointFailure

    data class IdentityMismatch(
        val requested: EvidenceCandidateIdentity,
        val observed: EvidenceCandidateIdentity,
    ) : EvidenceCandidateCheckpointFailure

    data class ShardConflict(val path: io.github.amichne.kast.workspace.contract.WorkspaceSourcePath) :
        EvidenceCandidateCheckpointFailure
}

sealed interface EvidenceCandidateCheckpointResolution {
    data class Checkpointed(
        val identity: EvidenceCandidateIdentity,
        val shards: List<EvidenceCandidateShard>,
    ) : EvidenceCandidateCheckpointResolution

    data class Rejected(val failure: EvidenceCandidateCheckpointFailure) : EvidenceCandidateCheckpointResolution
}

sealed interface EvidenceCandidateResumeFailure {
    data class IdentityMismatch(
        val requested: EvidenceCandidateIdentity,
        val observed: EvidenceCandidateIdentity,
    ) : EvidenceCandidateResumeFailure
}

sealed interface EvidenceCandidateResumeResolution {
    data object Absent : EvidenceCandidateResumeResolution

    data class Resumable(
        val identity: EvidenceCandidateIdentity,
        val shards: List<EvidenceCandidateShard>,
    ) : EvidenceCandidateResumeResolution

    data class Rejected(val failure: EvidenceCandidateResumeFailure) : EvidenceCandidateResumeResolution
}

sealed interface EvidenceCandidateDiscardResolution {
    data object Absent : EvidenceCandidateDiscardResolution

    data class Discarded(val identity: EvidenceCandidateIdentity) : EvidenceCandidateDiscardResolution

    data class Rejected(
        val requested: EvidenceCandidateIdentity,
        val observed: EvidenceCandidateIdentity,
    ) : EvidenceCandidateDiscardResolution

    data object WorkspaceWriteActive : EvidenceCandidateDiscardResolution
}

data class PublishedPersistedEvidenceSet(
    val identity: EvidenceCandidateIdentity,
    val revision: EvidenceLaneRevision,
    val shards: List<EvidenceCandidateShard>,
)

sealed interface PreviousPersistedEvidencePublication {
    data object Absent : PreviousPersistedEvidencePublication

    data class Retained(val publication: PublishedPersistedEvidenceSet) : PreviousPersistedEvidencePublication
}

sealed interface PersistedEvidenceLanePublicationState {
    data object Unpublished : PersistedEvidenceLanePublicationState

    data class Published(
        val current: PublishedPersistedEvidenceSet,
        val previous: PreviousPersistedEvidencePublication,
    ) : PersistedEvidenceLanePublicationState
}

sealed interface PersistedEvidenceLanePublicationExpectation {
    data object Unpublished : PersistedEvidenceLanePublicationExpectation

    data class Published(
        val identity: EvidenceCandidateIdentity,
        val revision: EvidenceLaneRevision,
    ) : PersistedEvidenceLanePublicationExpectation
}

sealed interface PersistedEvidenceLanePublicationFailure {
    data object WorkspaceWriteActive : PersistedEvidenceLanePublicationFailure

    data class CandidateAbsent(val lane: PersistedEvidenceLane) : PersistedEvidenceLanePublicationFailure

    data class CandidateIdentityMismatch(
        val requested: EvidenceCandidateIdentity,
        val observed: EvidenceCandidateIdentity,
    ) : PersistedEvidenceLanePublicationFailure

    data class LaneMoved(
        val expected: PersistedEvidenceLanePublicationExpectation,
        val observed: PersistedEvidenceLanePublicationState,
    ) : PersistedEvidenceLanePublicationFailure
}

sealed interface PersistedEvidenceLanePublicationResolution {
    data class Published(
        val publication: PersistedEvidenceLanePublicationState.Published,
    ) : PersistedEvidenceLanePublicationResolution

    data class Rejected(
        val failure: PersistedEvidenceLanePublicationFailure,
    ) : PersistedEvidenceLanePublicationResolution
}
