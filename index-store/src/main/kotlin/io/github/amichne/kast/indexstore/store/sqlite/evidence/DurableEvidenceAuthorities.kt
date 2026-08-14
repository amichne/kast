package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.snapshot.PublicationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateCheckpointResolution
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateDiscardResolution
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateResumeResolution
import io.github.amichne.kast.indexstore.snapshot.evidence.DurableEvidenceCandidateBatch
import io.github.amichne.kast.indexstore.snapshot.evidence.DurableEvidenceCandidateIdentity
import io.github.amichne.kast.indexstore.snapshot.evidence.DurableEvidenceLane
import io.github.amichne.kast.indexstore.snapshot.evidence.EvidenceLanePublicationExpectation
import io.github.amichne.kast.indexstore.snapshot.evidence.EvidenceLanePublicationResolution
import io.github.amichne.kast.indexstore.snapshot.evidence.EvidenceLanePublicationState

/** Candidate-only capability; it exposes no published-lane reader or pointer mutation. */
class DurableEvidenceCandidateCheckpointStore internal constructor(
    private val persistence: DurableEvidenceLaneStore,
) {
    fun checkpoint(
        identity: DurableEvidenceCandidateIdentity,
        batch: DurableEvidenceCandidateBatch,
    ): CandidateCheckpointResolution = persistence.checkpoint(identity, batch)

    fun resume(identity: DurableEvidenceCandidateIdentity): CandidateResumeResolution = persistence.resume(identity)

    fun discard(identity: DurableEvidenceCandidateIdentity): CandidateDiscardResolution = persistence.discard(identity)
}

/** Published-lane capability; candidate checkpoints are not observable through this type. */
class DurableEvidenceLanePublicationStore internal constructor(
    private val persistence: DurableEvidenceLaneStore,
) {
    fun published(lane: DurableEvidenceLane): EvidenceLanePublicationState = persistence.published(lane)

    fun publish(
        identity: DurableEvidenceCandidateIdentity,
        expectation: EvidenceLanePublicationExpectation,
        publishedAt: PublicationEpochMillis,
    ): EvidenceLanePublicationResolution = persistence.publish(identity, expectation, publishedAt)
}
