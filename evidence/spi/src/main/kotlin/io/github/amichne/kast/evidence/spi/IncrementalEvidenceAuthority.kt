package io.github.amichne.kast.evidence.spi

import io.github.amichne.kast.evidence.contract.*

/** Host-neutral writer-only capability for durable, restart-resumable candidate checkpoints. */
interface EvidenceCandidateCheckpointAuthority {
    fun checkpoint(
        identity: EvidenceCandidateIdentity,
        batch: EvidenceCandidateBatch,
    ): EvidenceCandidateCheckpointResolution

    fun resume(identity: EvidenceCandidateIdentity): EvidenceCandidateResumeResolution

    fun discard(identity: EvidenceCandidateIdentity): EvidenceCandidateDiscardResolution
}

/**
 * Host-neutral authority for atomic persisted-lane reads and publication.
 *
 * This capability exposes only current and explicitly retained-previous publication sets. It
 * cannot inspect or resume an unpublished candidate.
 */
interface PersistedEvidenceLanePublicationAuthority {
    fun published(lane: PersistedEvidenceLane): PersistedEvidenceLanePublicationState

    fun publish(
        identity: EvidenceCandidateIdentity,
        expectation: PersistedEvidenceLanePublicationExpectation,
    ): PersistedEvidenceLanePublicationResolution
}
