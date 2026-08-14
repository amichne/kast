package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.snapshot.EvidenceRevision
import io.github.amichne.kast.indexstore.snapshot.EvidenceRevisionResolution
import io.github.amichne.kast.indexstore.snapshot.PublicationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceIdentity
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateContentHash
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateEnvironmentFingerprint
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateShardPath
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateShardPayload
import io.github.amichne.kast.indexstore.snapshot.evidence.CandidateStageVersion
import io.github.amichne.kast.indexstore.snapshot.evidence.DurableEvidenceCandidateIdentity
import io.github.amichne.kast.indexstore.snapshot.evidence.DurableEvidenceCandidateShard
import io.github.amichne.kast.indexstore.snapshot.evidence.DurableEvidenceLane
import io.github.amichne.kast.indexstore.snapshot.evidence.DurableEvidenceRecordFailure
import io.github.amichne.kast.indexstore.snapshot.evidence.EvidenceValueRefinement
import io.github.amichne.kast.indexstore.snapshot.evidence.InvalidDurableEvidenceRecordException
import java.util.UUID

internal enum class CandidateSetIdFailure {
    INVALID_UUID,
}

@JvmInline
internal value class CandidateSetId private constructor(val value: String) {
    companion object {
        fun create(): CandidateSetId = CandidateSetId(UUID.randomUUID().toString())

        /**
         * Proof transition: `String -> EvidenceValueRefinement<CandidateSetId,
         * CandidateSetIdFailure>`.
         *
         * Establishes one canonical durable-set UUID. Rejection is closed [CandidateSetIdFailure]
         * data. Raw text may enter only from SQLite.
         */
        fun refine(raw: String): EvidenceValueRefinement<CandidateSetId, CandidateSetIdFailure> = try {
            val parsed = UUID.fromString(raw)
            if (parsed.toString() == raw) {
                EvidenceValueRefinement.Refined(CandidateSetId(raw))
            } else {
                EvidenceValueRefinement.Rejected(CandidateSetIdFailure.INVALID_UUID)
            }
        } catch (_: IllegalArgumentException) {
            EvidenceValueRefinement.Rejected(CandidateSetIdFailure.INVALID_UUID)
        }
    }
}

internal data class CandidateSetHeader(
    val setId: CandidateSetId,
    val identity: DurableEvidenceCandidateIdentity,
)

internal sealed interface LanePublicationRecord {
    data object Unpublished : LanePublicationRecord

    data class Published(
        val state: io.github.amichne.kast.indexstore.snapshot.evidence.EvidenceLanePublicationState.Published,
        val currentSetId: CandidateSetId,
    ) : LanePublicationRecord
}

/**
 * Proof transition: persisted lane/workspace/environment columns ->
 * `EvidenceValueRefinement<DurableEvidenceCandidateIdentity, DurableEvidenceRecordFailure>`.
 *
 * Establishes one closed lane and exact non-blank workspace plus SHA-256 environment identity.
 * Rejection is finite [DurableEvidenceRecordFailure] data. Raw columns enter only from SQLite.
 */
internal fun decodeCandidateIdentity(
    lane: String,
    workspace: String,
    environment: String,
): EvidenceValueRefinement<DurableEvidenceCandidateIdentity, DurableEvidenceRecordFailure> {
    val parsedLane = DurableEvidenceLane.entries.firstOrNull { candidate -> candidate.name == lane }
        ?: return EvidenceValueRefinement.Rejected(DurableEvidenceRecordFailure.InvalidLane(lane))
    if (workspace.isBlank()) {
        return EvidenceValueRefinement.Rejected(DurableEvidenceRecordFailure.BlankWorkspaceIdentity)
    }
    val parsedEnvironment = when (val refinement = CandidateEnvironmentFingerprint.refine(environment)) {
        is EvidenceValueRefinement.Refined -> refinement.value
        is EvidenceValueRefinement.Rejected -> return EvidenceValueRefinement.Rejected(
            DurableEvidenceRecordFailure.InvalidEnvironment(refinement.failure),
        )
    }
    return EvidenceValueRefinement.Refined(
        DurableEvidenceCandidateIdentity(
            lane = parsedLane,
            workspace = PublishedWorkspaceIdentity(workspace),
            environment = parsedEnvironment,
        ),
    )
}

/**
 * Proof transition: persisted shard columns ->
 * `EvidenceValueRefinement<DurableEvidenceCandidateShard, DurableEvidenceRecordFailure>`.
 *
 * Establishes normalized path, canonical content hash, bounded producer version, and bounded
 * payload evidence. Rejection is finite [DurableEvidenceRecordFailure] data. Raw columns enter
 * only from SQLite.
 */
internal fun decodeCandidateShard(
    path: String,
    contentHash: String,
    stageVersion: String,
    payload: String,
): EvidenceValueRefinement<DurableEvidenceCandidateShard, DurableEvidenceRecordFailure> {
    val parsedPath = when (val refinement = CandidateShardPath.refine(path)) {
        is EvidenceValueRefinement.Refined -> refinement.value
        is EvidenceValueRefinement.Rejected -> return EvidenceValueRefinement.Rejected(
            DurableEvidenceRecordFailure.InvalidPath(refinement.failure),
        )
    }
    val parsedHash = when (val refinement = CandidateContentHash.refine(contentHash)) {
        is EvidenceValueRefinement.Refined -> refinement.value
        is EvidenceValueRefinement.Rejected -> return EvidenceValueRefinement.Rejected(
            DurableEvidenceRecordFailure.InvalidContentHash(refinement.failure),
        )
    }
    val parsedVersion = when (val refinement = CandidateStageVersion.refine(stageVersion)) {
        is EvidenceValueRefinement.Refined -> refinement.value
        is EvidenceValueRefinement.Rejected -> return EvidenceValueRefinement.Rejected(
            DurableEvidenceRecordFailure.InvalidStageVersion(refinement.failure),
        )
    }
    val parsedPayload = when (val refinement = CandidateShardPayload.refine(payload)) {
        is EvidenceValueRefinement.Refined -> refinement.value
        is EvidenceValueRefinement.Rejected -> return EvidenceValueRefinement.Rejected(
            DurableEvidenceRecordFailure.InvalidPayload(refinement.failure),
        )
    }
    return EvidenceValueRefinement.Refined(
        DurableEvidenceCandidateShard(parsedPath, parsedHash, parsedVersion, parsedPayload),
    )
}

internal fun positiveEvidenceRevision(raw: Long): EvidenceRevision {
    if (raw <= 0) throw InvalidDurableEvidenceRecordException(DurableEvidenceRecordFailure.InvalidRevision(raw))
    return when (val resolution = EvidenceRevision.fromPersisted(raw)) {
        is EvidenceRevisionResolution.Resolved -> resolution.revision
        is EvidenceRevisionResolution.Rejected ->
            throw InvalidDurableEvidenceRecordException(DurableEvidenceRecordFailure.InvalidRevision(raw))
    }
}

internal fun publicationEpoch(raw: Long): PublicationEpochMillis {
    if (raw < 0) {
        throw InvalidDurableEvidenceRecordException(DurableEvidenceRecordFailure.InvalidPublicationTime(raw))
    }
    return PublicationEpochMillis.fromClock(raw)
}

internal fun <Value, Failure> EvidenceValueRefinement<Value, Failure>.orInvalid(
    mapFailure: (Failure) -> DurableEvidenceRecordFailure,
): Value = when (this) {
    is EvidenceValueRefinement.Refined -> value
    is EvidenceValueRefinement.Rejected -> throw InvalidDurableEvidenceRecordException(mapFailure(failure))
}
