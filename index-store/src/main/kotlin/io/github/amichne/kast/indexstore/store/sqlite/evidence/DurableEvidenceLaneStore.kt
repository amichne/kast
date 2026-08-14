package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.snapshot.EvidenceRevision
import io.github.amichne.kast.indexstore.snapshot.PublicationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.evidence.*
import java.sql.Connection

/**
 * Durable candidate-checkpoint and independent evidence-lane publication authority.
 *
 * Candidate shards live in the canonical source-index database but are reachable only through
 * [resume]. Published readers follow atomic lane pointers and therefore cannot observe a partial
 * candidate batch.
 */
internal class DurableEvidenceLaneStore(
    private val state: SqliteSourceIndexStoreState,
) {
    fun checkpoint(
        identity: DurableEvidenceCandidateIdentity,
        batch: DurableEvidenceCandidateBatch,
    ): CandidateCheckpointResolution = when (val transaction = state.committedWriteTransaction { connection ->
        val observed = readCandidateHeader(connection, identity.lane)
        val candidate = when {
            observed == null -> createCandidateHeader(connection, identity)
            observed.identity == identity -> observed
            else -> return@committedWriteTransaction CandidateCheckpointResolution.Rejected(
                CandidateCheckpointFailure.IdentityMismatch(identity, observed.identity),
            )
        }
        val existing = batch.shards.associateWith { shard ->
            readCandidateShard(connection, candidate.setId, identity.lane, shard.path)
        }
        existing.entries.firstOrNull { (requested, persisted) ->
            persisted != null && persisted != requested
        }?.let { (requested, _) ->
            return@committedWriteTransaction CandidateCheckpointResolution.Rejected(
                CandidateCheckpointFailure.ShardConflict(requested.path),
            )
        }
        val missing = existing.filterValues { persisted -> persisted == null }.keys
        missing.forEach { shard -> insertCandidateShard(connection, candidate.setId, identity.lane, shard) }
        if (observed == null || missing.isNotEmpty()) state.incrementGenerationInTransaction(connection)
        CandidateCheckpointResolution.Checkpointed(
            identity,
            readCandidateShards(connection, candidate.setId, identity.lane),
        )
    }) {
        is CommittedSourceIndexWrite.Committed -> transaction.value
        CommittedSourceIndexWrite.WorkspaceWriteActive -> CandidateCheckpointResolution.Rejected(
            CandidateCheckpointFailure.WorkspaceWriteActive,
        )
    }

    fun resume(identity: DurableEvidenceCandidateIdentity): CandidateResumeResolution =
        synchronized(state.writeLock) {
            val connection = state.connection()
            val observed = readCandidateHeader(connection, identity.lane)
                ?: return@synchronized CandidateResumeResolution.Absent
            if (observed.identity != identity) {
                return@synchronized CandidateResumeResolution.Rejected(
                    CandidateResumeFailure.IdentityMismatch(identity, observed.identity),
                )
            }
            CandidateResumeResolution.Resumable(
                identity,
                readCandidateShards(connection, observed.setId, identity.lane),
            )
        }

    fun discard(identity: DurableEvidenceCandidateIdentity): CandidateDiscardResolution =
        when (val transaction = state.committedWriteTransaction { connection ->
            val observed = readCandidateHeader(connection, identity.lane)
                ?: return@committedWriteTransaction CandidateDiscardResolution.Absent
            if (observed.identity != identity) {
                return@committedWriteTransaction CandidateDiscardResolution.Rejected(identity, observed.identity)
            }
            connection.prepareStatement("DELETE FROM evidence_lane_candidates WHERE lane = ? AND set_id = ?").use {
                statement ->
                statement.setString(1, identity.lane.name)
                statement.setString(2, observed.setId.value)
                check(statement.executeUpdate() == 1) { "Durable evidence candidate pointer was not removed" }
            }
            connection.prepareStatement("DELETE FROM evidence_lane_sets WHERE set_id = ? AND lane = ?").use { statement ->
                statement.setString(1, observed.setId.value)
                statement.setString(2, identity.lane.name)
                check(statement.executeUpdate() == 1) { "Durable evidence candidate set was not removed" }
            }
            state.incrementGenerationInTransaction(connection)
            CandidateDiscardResolution.Discarded(identity)
        }) {
            is CommittedSourceIndexWrite.Committed -> transaction.value
            CommittedSourceIndexWrite.WorkspaceWriteActive -> CandidateDiscardResolution.WorkspaceWriteActive
        }

    fun published(lane: DurableEvidenceLane): EvidenceLanePublicationState = synchronized(state.writeLock) {
        readPublicationRecord(state.connection(), lane).state()
    }

    fun publish(
        identity: DurableEvidenceCandidateIdentity,
        expectation: EvidenceLanePublicationExpectation,
        publishedAt: PublicationEpochMillis,
    ): EvidenceLanePublicationResolution = when (val transaction = state.committedWriteTransaction { connection ->
        val candidate = readCandidateHeader(connection, identity.lane)
            ?: return@committedWriteTransaction EvidenceLanePublicationResolution.Rejected(
                EvidenceLanePublicationFailure.CandidateAbsent(identity.lane),
            )
        if (candidate.identity != identity) {
            return@committedWriteTransaction EvidenceLanePublicationResolution.Rejected(
                EvidenceLanePublicationFailure.CandidateIdentityMismatch(identity, candidate.identity),
            )
        }
        val observed = readPublicationRecord(connection, identity.lane)
        when (val admission = expectation.admit(observed.state())) {
            EvidenceLanePublicationCas.Admitted -> Unit
            is EvidenceLanePublicationCas.Rejected ->
                return@committedWriteTransaction EvidenceLanePublicationResolution.Rejected(admission.failure)
        }
        val revision = when (observed) {
            LanePublicationRecord.Unpublished -> EvidenceRevision.first()
            is LanePublicationRecord.Published -> observed.state.current.revision.next()
        }
        val shards = readCandidateShards(connection, candidate.setId, identity.lane)
        val previous = when (observed) {
            LanePublicationRecord.Unpublished -> PreviousEvidenceLanePublication.Absent
            is LanePublicationRecord.Published -> PreviousEvidenceLanePublication.Retained(observed.state.current)
        }
        writePublicationPointer(connection, candidate, revision, publishedAt, observed)
        connection.prepareStatement("DELETE FROM evidence_lane_candidates WHERE lane = ? AND set_id = ?").use {
                statement ->
            statement.setString(1, identity.lane.name)
            statement.setString(2, candidate.setId.value)
            check(statement.executeUpdate() == 1) { "Published candidate pointer was not removed" }
        }
        state.incrementGenerationInTransaction(connection)
        EvidenceLanePublicationResolution.Published(
            EvidenceLanePublicationState.Published(
                current = PublishedEvidenceCandidateSet(identity, revision, publishedAt, shards),
                previous = previous,
            ),
        )
    }) {
        is CommittedSourceIndexWrite.Committed -> transaction.value
        CommittedSourceIndexWrite.WorkspaceWriteActive -> EvidenceLanePublicationResolution.Rejected(
            EvidenceLanePublicationFailure.WorkspaceWriteActive,
        )
    }

    private fun createCandidateHeader(
        connection: Connection,
        identity: DurableEvidenceCandidateIdentity,
    ): CandidateSetHeader {
        val setId = CandidateSetId.create()
        connection.prepareStatement(
            """INSERT INTO evidence_lane_sets(set_id, lane, workspace_identity, environment_fingerprint)
               VALUES (?, ?, ?, ?)""",
        ).use { statement ->
            statement.setString(1, setId.value)
            statement.setString(2, identity.lane.name)
            statement.setString(3, identity.workspace.value)
            statement.setString(4, identity.environment.value)
            check(statement.executeUpdate() == 1) { "Durable evidence candidate set was not created" }
        }
        connection.prepareStatement("INSERT INTO evidence_lane_candidates(lane, set_id) VALUES (?, ?)").use { statement ->
            statement.setString(1, identity.lane.name)
            statement.setString(2, setId.value)
            check(statement.executeUpdate() == 1) { "Durable evidence candidate pointer was not created" }
        }
        return CandidateSetHeader(setId, identity)
    }

    private fun readCandidateHeader(connection: Connection, lane: DurableEvidenceLane): CandidateSetHeader? =
        connection.prepareStatement(
            """SELECT sets.set_id, sets.lane, sets.workspace_identity, sets.environment_fingerprint
               FROM evidence_lane_candidates candidates
               JOIN evidence_lane_sets sets ON sets.set_id = candidates.set_id AND sets.lane = candidates.lane
               WHERE candidates.lane = ?""",
        ).use { statement ->
            statement.setString(1, lane.name)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                CandidateSetHeader(
                    CandidateSetId.refine(rows.getString("set_id")).orInvalid {
                        DurableEvidenceRecordFailure.InvalidSetId
                    },
                    decodeCandidateIdentity(
                        rows.getString("lane"),
                        rows.getString("workspace_identity"),
                        rows.getString("environment_fingerprint"),
                    ).orInvalid { failure -> failure },
                )
            }
        }

    private fun insertCandidateShard(
        connection: Connection,
        setId: CandidateSetId,
        lane: DurableEvidenceLane,
        shard: DurableEvidenceCandidateShard,
    ) {
        connection.prepareStatement(
            """INSERT INTO evidence_candidate_shards(
                   set_id, lane, source_path, content_hash, stage_version, payload
               ) VALUES (?, ?, ?, ?, ?, ?)""",
        ).use { statement ->
            statement.setString(1, setId.value)
            statement.setString(2, lane.name)
            statement.setString(3, shard.path.value)
            statement.setString(4, shard.contentHash.value)
            statement.setString(5, shard.stageVersion.value)
            statement.setString(6, shard.payload.value)
            check(statement.executeUpdate() == 1) { "Durable evidence candidate shard was not written" }
        }
    }

    private fun readCandidateShard(
        connection: Connection,
        setId: CandidateSetId,
        lane: DurableEvidenceLane,
        path: CandidateShardPath,
    ): DurableEvidenceCandidateShard? = connection.prepareStatement(
        """SELECT source_path, content_hash, stage_version, payload
           FROM evidence_candidate_shards WHERE set_id = ? AND lane = ? AND source_path = ?""",
    ).use { statement ->
        statement.setString(1, setId.value)
        statement.setString(2, lane.name)
        statement.setString(3, path.value)
        statement.executeQuery().use { rows ->
            if (!rows.next()) null else decodeShardRow(rows)
        }
    }

    private fun readCandidateShards(
        connection: Connection,
        setId: CandidateSetId,
        lane: DurableEvidenceLane,
    ): List<DurableEvidenceCandidateShard> = connection.prepareStatement(
        """SELECT source_path, content_hash, stage_version, payload
           FROM evidence_candidate_shards WHERE set_id = ? AND lane = ? ORDER BY source_path""",
    ).use { statement ->
        statement.setString(1, setId.value)
        statement.setString(2, lane.name)
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(decodeShardRow(rows)) }
        }
    }

    private fun decodeShardRow(rows: java.sql.ResultSet): DurableEvidenceCandidateShard =
        decodeCandidateShard(
            rows.getString("source_path"),
            rows.getString("content_hash"),
            rows.getString("stage_version"),
            rows.getString("payload"),
        ).orInvalid { failure -> failure }

    private fun readPublicationRecord(connection: Connection, lane: DurableEvidenceLane): LanePublicationRecord =
        connection.prepareStatement(
            """SELECT current_set_id, current_revision, current_published_at_epoch_millis,
                      previous_set_id, previous_revision, previous_published_at_epoch_millis
               FROM evidence_lane_publications WHERE lane = ?""",
        ).use { statement ->
            statement.setString(1, lane.name)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use LanePublicationRecord.Unpublished
                val currentSetId = CandidateSetId.refine(rows.getString("current_set_id")).orInvalid {
                    DurableEvidenceRecordFailure.InvalidSetId
                }
                val current = readPublishedSet(
                    connection,
                    currentSetId,
                    lane,
                    positiveEvidenceRevision(rows.getLong("current_revision")),
                    publicationEpoch(rows.getLong("current_published_at_epoch_millis")),
                )
                val previousSetId = rows.getString("previous_set_id")?.let { raw ->
                    CandidateSetId.refine(raw).orInvalid { DurableEvidenceRecordFailure.InvalidSetId }
                }
                val previous = if (previousSetId == null) {
                    PreviousEvidenceLanePublication.Absent
                } else {
                    PreviousEvidenceLanePublication.Retained(
                        readPublishedSet(
                            connection,
                            previousSetId,
                            lane,
                            positiveEvidenceRevision(rows.getLong("previous_revision")),
                            publicationEpoch(rows.getLong("previous_published_at_epoch_millis")),
                        ),
                    )
                }
                LanePublicationRecord.Published(
                    EvidenceLanePublicationState.Published(current, previous),
                    currentSetId,
                )
            }
        }

    private fun readPublishedSet(
        connection: Connection,
        setId: CandidateSetId,
        lane: DurableEvidenceLane,
        revision: EvidenceRevision,
        publishedAt: PublicationEpochMillis,
    ): PublishedEvidenceCandidateSet = connection.prepareStatement(
        """SELECT lane, workspace_identity, environment_fingerprint
           FROM evidence_lane_sets WHERE set_id = ? AND lane = ?""",
    ).use { statement ->
        statement.setString(1, setId.value)
        statement.setString(2, lane.name)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "Published evidence lane set is missing" }
            PublishedEvidenceCandidateSet(
                decodeCandidateIdentity(
                    rows.getString("lane"),
                    rows.getString("workspace_identity"),
                    rows.getString("environment_fingerprint"),
                ).orInvalid { failure -> failure },
                revision,
                publishedAt,
                readCandidateShards(connection, setId, lane),
            )
        }
    }

    private fun writePublicationPointer(
        connection: Connection,
        candidate: CandidateSetHeader,
        revision: EvidenceRevision,
        publishedAt: PublicationEpochMillis,
        observed: LanePublicationRecord,
    ) {
        connection.prepareStatement(
            """INSERT INTO evidence_lane_publications(
                   lane, current_set_id, current_revision, current_published_at_epoch_millis,
                   previous_set_id, previous_revision, previous_published_at_epoch_millis
               ) VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(lane) DO UPDATE SET
                   current_set_id = excluded.current_set_id,
                   current_revision = excluded.current_revision,
                   current_published_at_epoch_millis = excluded.current_published_at_epoch_millis,
                   previous_set_id = excluded.previous_set_id,
                   previous_revision = excluded.previous_revision,
                   previous_published_at_epoch_millis = excluded.previous_published_at_epoch_millis""",
        ).use { statement ->
            statement.setString(1, candidate.identity.lane.name)
            statement.setString(2, candidate.setId.value)
            statement.setLong(3, revision.value)
            statement.setLong(4, publishedAt.value)
            when (observed) {
                LanePublicationRecord.Unpublished -> {
                    statement.setNull(5, java.sql.Types.VARCHAR)
                    statement.setNull(6, java.sql.Types.BIGINT)
                    statement.setNull(7, java.sql.Types.BIGINT)
                }
                is LanePublicationRecord.Published -> {
                    statement.setString(5, observed.currentSetId.value)
                    statement.setLong(6, observed.state.current.revision.value)
                    statement.setLong(7, observed.state.current.publishedAt.value)
                }
            }
            check(statement.executeUpdate() == 1) { "Durable evidence lane pointer was not published" }
        }
    }

    private fun LanePublicationRecord.state(): EvidenceLanePublicationState = when (this) {
        LanePublicationRecord.Unpublished -> EvidenceLanePublicationState.Unpublished
        is LanePublicationRecord.Published -> state
    }
}
