package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.indexstore.snapshot.*
import java.sql.Connection

internal class SourceIndexSnapshotStore(
    private val state: SqliteSourceIndexStoreState,
) {
    private val dbPath get() = state.dbPath
    fun readWorkspaceDiscovery(cacheKey: String): String? {
        synchronized(state.writeLock) {
            val conn = state.connection()
            val discovery = state.readTable(SourceIndexReadTable.WORKSPACE_DISCOVERY)
            return conn.prepareStatement(
                "SELECT payload FROM $discovery WHERE cache_key = ?",
            ).use { stmt ->
                stmt.setString(1, cacheKey)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getString(1) else null
            }
        }
    }

    fun writeWorkspaceDiscovery(cacheKey: String, schemaVersion: Int, payload: String) {
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.prepareStatement(
                "INSERT OR REPLACE INTO workspace_discovery (cache_key, schema_version, payload) VALUES (?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, cacheKey)
                stmt.setInt(2, schemaVersion)
                stmt.setString(3, payload)
                stmt.executeUpdate()
            }
        }
    }

    fun readGeneration(): SourceIndexGeneration {
        synchronized(state.writeLock) {
            val conn = state.connection()
            return try {
                state.readGenerationInTransaction(conn)
            } catch (_: Exception) {
                SourceIndexGeneration(0)
            }
        }
    }

    /**
     * Proof transition:
     * `(SnapshotExportTarget, GitObjectId, ProducerVersion) -> PublicationEvidence`.
     *
     * Exports only through a capability carrying an absent private SQLite
     * destination and returns the before/after generation plus completeness,
     * schema, tree, and producer evidence required by snapshot publication.
     * Raw paths and counts exist only at the SQLite boundary.
     */
    fun exportSnapshotDatabase(
        target: SnapshotExportTarget,
        treeOid: GitObjectId,
        producerVersion: ProducerVersion,
    ): PublicationEvidence = synchronized(state.writeLock) {
        val conn = state.connection()
        val generationBefore = state.readGenerationInTransaction(conn)
        val (moduleProgressCount, incompleteModuleCount) = conn.createStatement().use { statement ->
            val result = statement.executeQuery(
                """SELECT COUNT(*) AS total,
                          SUM(CASE
                              WHEN relationship_index_status NOT IN ('COMPLETE','DEGRADED')
                               OR indexed_file_count != total_file_count
                                   THEN 1 ELSE 0 END) AS incomplete
                   FROM module_index_progress""",
            )
            check(result.next())
            result.getInt("total") to result.getInt("incomplete")
        }
        val pendingCount = conn.createStatement().use { statement ->
            val result = statement.executeQuery("SELECT COUNT(*) FROM pending_updates WHERE applied = 0")
            check(result.next())
            result.getInt(1)
        }
        val escapedTarget = target.database.value.replace("'", "''")
        conn.createStatement().use { statement -> statement.execute("VACUUM INTO '$escapedTarget'") }
        val generationAfter = state.readGenerationInTransaction(conn)
        PublicationEvidence(
            generationBefore = generationBefore,
            generationAfter = generationAfter,
            moduleProgressCount = NonNegativeInt(moduleProgressCount),
            incompleteModuleCount = NonNegativeInt(incompleteModuleCount),
            pendingCount = NonNegativeInt(pendingCount),
            treeOid = treeOid,
            indexSchema = SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION),
            producerVersion = producerVersion,
        )
    }

    fun readWorkspacePublication(): PublishedWorkspaceGenerationState = synchronized(state.writeLock) {
        readWorkspacePublicationInTransaction(state.connection())
    }

    fun prepareWorkspacePublication(
        session: WorkspaceWriteSession,
        identity: PublishedWorkspaceIdentity,
        publishedAt: PublicationEpochMillis,
        graphBlocker: GraphEvidenceBlocker?,
    ): PublishedWorkspaceGenerationManifest = state.inspectWorkspaceWrite(session) { conn ->
        val sourceIndexGeneration = state.readGenerationInTransaction(conn)
        val moduleProgress = state.readTable(SourceIndexReadTable.MODULE_INDEX_PROGRESS)
        val (moduleProgressCount, incompleteModuleCount) = conn.createStatement().use { statement ->
            val result = statement.executeQuery(
                """SELECT COUNT(*) AS total,
                          SUM(CASE
                              WHEN relationship_index_status NOT IN ('COMPLETE','DEGRADED')
                               OR indexed_file_count != total_file_count
                                  THEN 1 ELSE 0 END) AS incomplete
                   FROM $moduleProgress""",
            )
            check(result.next())
            NonNegativeInt(result.getInt("total")) to NonNegativeInt(result.getInt("incomplete"))
        }
        val pendingUpdateCount = conn.createStatement().use { statement ->
            val result = statement.executeQuery("SELECT COUNT(*) FROM pending_updates WHERE applied = 0")
            check(result.next())
            NonNegativeInt(result.getInt(1))
        }
        val readiness = when (
            val resolution = deriveWorkspacePublicationReadiness(
                sourceIndexGeneration,
                moduleProgressCount,
                incompleteModuleCount,
                pendingUpdateCount,
            )
        ) {
            is WorkspacePublicationReadinessResolution.Ready -> resolution.proof
            is WorkspacePublicationReadinessResolution.Rejected ->
                throw WorkspacePublicationRejectedException(resolution.failure)
        }
        val previous = readWorkspacePublicationInTransaction(conn)
        PublishedWorkspaceGenerationManifest(
            generation = when (previous) {
                PublishedWorkspaceGenerationState.Unpublished -> WorkspaceSemanticGeneration(1)
                is PublishedWorkspaceGenerationState.Published -> previous.manifest.generation.next()
            },
            identity = identity,
            sourceIndexGeneration = readiness.sourceIndexGeneration,
            sourceRevision = EvidenceRevision.fromSourceIndexGeneration(readiness.sourceIndexGeneration),
            referenceRevision = EvidenceRevision.fromSourceIndexGeneration(readiness.sourceIndexGeneration),
            graphPublication = graphBlocker?.let(GraphEvidencePublication::Blocked)
                ?: GraphEvidencePublication.Ready(
                    EvidenceRevision.fromSourceIndexGeneration(readiness.sourceIndexGeneration),
                ),
            sourceIndexSchemaVersion = SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION),
            publishedAt = publishedAt,
            repositoryOverlay = state.repositoryOverlayPublication,
        )
    }

    fun commitWorkspacePublication(
        session: WorkspaceWriteSession,
        manifest: PublishedWorkspaceGenerationManifest,
    ): PublishedWorkspaceGenerationManifest = state.commitWorkspaceWrite(session) { conn ->
        val activeSchema = SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION)
        val sourceIndexGeneration = state.readGenerationInTransaction(conn)
        val previous = readWorkspacePublicationInTransaction(conn)
        val expectedRevision = when (previous) {
            PublishedWorkspaceGenerationState.Unpublished -> WorkspaceSemanticGeneration(1)
            is PublishedWorkspaceGenerationState.Published -> previous.manifest.generation.next()
        }
        val commit = when (
            val resolution = deriveWorkspacePublicationCommit(
                manifest,
                activeSchema,
                sourceIndexGeneration,
                expectedRevision,
            )
        ) {
            is WorkspacePublicationCommitResolution.Proven -> resolution.proof
            is WorkspacePublicationCommitResolution.Rejected ->
                throw WorkspacePublicationCommitRejectedException(resolution.failure)
        }
        conn.prepareStatement(
            """INSERT INTO workspace_publication(
                   singleton, revision, identity, source_index_generation,
                   source_revision, reference_revision, graph_revision, graph_blocker,
                   source_index_schema_version, published_at_epoch_millis, repository_overlay_file
               ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(singleton) DO UPDATE SET
                   revision = excluded.revision,
                   identity = excluded.identity,
                   source_index_generation = excluded.source_index_generation,
                   source_revision = excluded.source_revision,
                   reference_revision = excluded.reference_revision,
                   graph_revision = excluded.graph_revision,
                   graph_blocker = excluded.graph_blocker,
                   source_index_schema_version = excluded.source_index_schema_version,
                   published_at_epoch_millis = excluded.published_at_epoch_millis,
                   repository_overlay_file = excluded.repository_overlay_file""",
        ).use { statement ->
            statement.setLong(1, commit.manifest.generation.value)
            statement.setString(2, commit.manifest.identity.value)
            statement.setLong(3, commit.manifest.sourceIndexGeneration.value)
            statement.setLong(4, commit.manifest.sourceRevision.value)
            statement.setLong(5, commit.manifest.referenceRevision.value)
            when (val graph = commit.manifest.graphPublication) {
                is GraphEvidencePublication.Ready -> {
                    statement.setLong(6, graph.revision.value)
                    statement.setNull(7, java.sql.Types.VARCHAR)
                }
                is GraphEvidencePublication.Blocked -> {
                    statement.setNull(6, java.sql.Types.BIGINT)
                    statement.setString(7, graph.blocker.name)
                }
            }
            statement.setInt(8, commit.manifest.sourceIndexSchemaVersion.value)
            statement.setLong(9, commit.manifest.publishedAt.value)
            statement.setString(10, commit.manifest.repositoryOverlay.serializedFileName())
            check(statement.executeUpdate() == 1) { "Workspace publication row was not written" }
        }
        commit.manifest
    }

    private fun readWorkspacePublicationInTransaction(conn: Connection): PublishedWorkspaceGenerationState =
        conn.prepareStatement(
            """SELECT revision, identity, source_index_generation, source_revision, reference_revision,
                      graph_revision, graph_blocker, source_index_schema_version,
                      published_at_epoch_millis, repository_overlay_file
               FROM workspace_publication
               WHERE singleton = 1""",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use PublishedWorkspaceGenerationState.Unpublished
                when (
                    val resolution = PublishedWorkspaceGenerationManifest.resolve(
                        SerializedWorkspacePublication(
                            generation = rows.getLong("revision"),
                            identity = rows.getString("identity"),
                            sourceIndexGeneration = rows.getLong("source_index_generation"),
                            sourceRevision = rows.getLong("source_revision"),
                            referenceRevision = rows.getLong("reference_revision"),
                            graphRevision = rows.getLong("graph_revision").let { if (rows.wasNull()) null else it },
                            graphBlocker = rows.getString("graph_blocker"),
                            sourceIndexSchemaVersion = rows.getInt("source_index_schema_version"),
                            publishedAtEpochMillis = rows.getLong("published_at_epoch_millis"),
                            repositoryOverlayFile = rows.getString("repository_overlay_file"),
                        ),
                    )
                ) {
                    is WorkspacePublicationRecordResolution.Resolved -> PublishedWorkspaceGenerationState.Published(
                        resolution.manifest,
                    )
                    is WorkspacePublicationRecordResolution.Rejected ->
                        throw InvalidWorkspacePublicationRecordException(resolution.failure)
                }
            }
        }

    /**
     * Proof transition:
     * `(SourceIndexGeneration, NonNegativeInt, NonNegativeInt, NonNegativeInt)`
     * `-> WorkspacePublicationReadinessResolution`.
     *
     * A ready value proves module progress exists, every module is complete,
     * and no pending update remains for the captured source-index generation.
     * Rejection is finite [WorkspacePublicationReadinessFailure] data. Raw SQL
     * counts are refined to [NonNegativeInt] before this transition.
     */
    private fun deriveWorkspacePublicationReadiness(
        sourceIndexGeneration: SourceIndexGeneration,
        moduleProgressCount: NonNegativeInt,
        incompleteModuleCount: NonNegativeInt,
        pendingUpdateCount: NonNegativeInt,
    ): WorkspacePublicationReadinessResolution = when {
        moduleProgressCount.value == 0 -> WorkspacePublicationReadinessResolution.Rejected(
            WorkspacePublicationReadinessFailure.ModuleProgressAbsent,
        )
        incompleteModuleCount.value != 0 -> WorkspacePublicationReadinessResolution.Rejected(
            WorkspacePublicationReadinessFailure.ModulesIncomplete(incompleteModuleCount),
        )
        pendingUpdateCount.value != 0 -> WorkspacePublicationReadinessResolution.Rejected(
            WorkspacePublicationReadinessFailure.PendingUpdates(pendingUpdateCount),
        )
        else -> WorkspacePublicationReadinessResolution.Ready(
            WorkspacePublicationReadiness(sourceIndexGeneration),
        )
    }

    /**
     * Proof transition:
     * `(PublishedWorkspaceGenerationManifest, SourceIndexSchemaVersion, SourceIndexGeneration, WorkspaceSemanticGeneration)`
     * `-> WorkspacePublicationCommitResolution`.
     *
     * A proven value binds the prepared manifest to the active schema, current
     * source-index generation, and next publication revision. Rejection is
     * finite [WorkspacePublicationCommitFailure] data; callers persist only the
     * returned [WorkspacePublicationCommitProof].
     */
    private fun deriveWorkspacePublicationCommit(
        manifest: PublishedWorkspaceGenerationManifest,
        activeSchema: SourceIndexSchemaVersion,
        sourceIndexGeneration: SourceIndexGeneration,
        expectedRevision: WorkspaceSemanticGeneration,
    ): WorkspacePublicationCommitResolution = when {
        manifest.sourceIndexSchemaVersion != activeSchema -> WorkspacePublicationCommitResolution.Rejected(
            WorkspacePublicationCommitFailure.SchemaMismatch(activeSchema, manifest.sourceIndexSchemaVersion),
        )
        manifest.sourceIndexGeneration != sourceIndexGeneration -> WorkspacePublicationCommitResolution.Rejected(
            WorkspacePublicationCommitFailure.SourceIndexGenerationMoved(
                manifest.sourceIndexGeneration,
                sourceIndexGeneration,
            ),
        )
        manifest.sourceRevision.value != sourceIndexGeneration.value ||
            manifest.referenceRevision.value != sourceIndexGeneration.value ||
            (manifest.graphPublication as? GraphEvidencePublication.Ready)?.revision?.value
                ?.let { it != sourceIndexGeneration.value } == true -> WorkspacePublicationCommitResolution.Rejected(
            WorkspacePublicationCommitFailure.EvidenceRevisionMismatch,
        )
        manifest.generation != expectedRevision -> WorkspacePublicationCommitResolution.Rejected(
            WorkspacePublicationCommitFailure.RevisionMoved(expectedRevision, manifest.generation),
        )
        else -> WorkspacePublicationCommitResolution.Proven(WorkspacePublicationCommitProof(manifest))
    }

    fun readHeadCommit(): String? {
        synchronized(state.writeLock) {
            val conn = state.connection()
            return try {
                conn.prepareStatement("SELECT head_commit FROM schema_version LIMIT 1").use { stmt ->
                    val rs = stmt.executeQuery()
                    if (rs.next()) rs.getString(1) else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun writeHeadCommit(sha: String) {
        synchronized(state.writeLock) {
            state.connection().prepareStatement("UPDATE schema_version SET head_commit = ?").use { stmt ->
                stmt.setString(1, sha)
                stmt.executeUpdate()
            }
        }
    }

}
