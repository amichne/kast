package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.api.client.WorkspaceRepository
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
import io.github.amichne.kast.indexstore.snapshot.RepositoryOverlayPublication
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabase
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabaseCandidate
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabaseFailure
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabaseResolution
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotLayout
import io.github.amichne.kast.indexstore.snapshot.SourceIndexSchemaVersion
import io.github.amichne.kast.indexstore.store.codec.PathInterningCodec
import io.github.amichne.kast.indexstore.store.codec.ReadOnlyInterningAliasFailure
import io.github.amichne.kast.indexstore.store.codec.ReadOnlyInterningAliasResolution
import io.github.amichne.kast.indexstore.store.codec.StringInterningCodec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.sql.Connection

internal sealed interface RepositoryOverlayAuthorityFailure {
    data class DescriptorInvalid(val path: NormalizedPath) : RepositoryOverlayAuthorityFailure

    data class DescriptorMalformed(val path: NormalizedPath) : RepositoryOverlayAuthorityFailure

    data class RepositoryUnavailable(val path: NormalizedPath) : RepositoryOverlayAuthorityFailure

    data class SnapshotRejected(
        val descriptor: NormalizedPath,
        val failure: RepositorySnapshotDatabaseFailure,
    ) : RepositoryOverlayAuthorityFailure

    data class BaseSchemaRejected(
        val database: NormalizedPath,
        val expected: SourceIndexSchemaVersion,
        val observed: RepositoryBaseSchemaObservation,
    ) : RepositoryOverlayAuthorityFailure

    data class InterningAliasesRejected(
        val failure: RepositoryInterningAliasFailure,
    ) : RepositoryOverlayAuthorityFailure
}

internal sealed interface RepositoryBaseSchemaObservation {
    data object Missing : RepositoryBaseSchemaObservation

    data class Invalid(val value: Int) : RepositoryBaseSchemaObservation

    data class Available(val version: SourceIndexSchemaVersion) : RepositoryBaseSchemaObservation
}

internal sealed interface RepositoryBaseAttachmentResolution {
    data object Attached : RepositoryBaseAttachmentResolution

    data class Rejected(val failure: RepositoryOverlayAuthorityFailure) : RepositoryBaseAttachmentResolution
}

internal sealed interface RepositoryInterningAliasFailure {
    data class PathAliasesRejected(
        val failure: ReadOnlyInterningAliasFailure,
    ) : RepositoryInterningAliasFailure

    data class FqNameAliasesRejected(
        val failure: ReadOnlyInterningAliasFailure,
    ) : RepositoryInterningAliasFailure
}

internal sealed interface RepositoryInterningAliasResolution {
    data object Loaded : RepositoryInterningAliasResolution

    data class Rejected(
        val failure: RepositoryInterningAliasFailure,
    ) : RepositoryInterningAliasResolution
}

internal class RepositoryOverlayAuthorityException(
    val failure: RepositoryOverlayAuthorityFailure,
) : IllegalStateException(failure.toString())

internal sealed interface RepositoryOverlayStateResolution {
    data class Resolved(val state: RepositoryOverlayState) : RepositoryOverlayStateResolution

    data class Rejected(val failure: RepositoryOverlayAuthorityFailure) : RepositoryOverlayStateResolution
}

private data class ValidatedRepositoryOverlay(
    val descriptor: NormalizedPath,
    val manifest: OverlayManifest,
    val base: RepositorySnapshotDatabase,
)

private sealed interface RepositoryReadAuthority {
    val publication: RepositoryOverlayPublication

    fun attachBase(connection: Connection): RepositoryBaseAttachmentResolution

    fun installReadAuthority(connection: Connection)

    fun loadInterningAliases(
        connection: Connection,
        pathCodec: PathInterningCodec,
        fqCodec: StringInterningCodec,
    ): RepositoryInterningAliasResolution

    fun readTable(table: SourceIndexReadTable): SqlReadRelation

    fun initialize(connection: Connection, incrementGeneration: (Connection) -> Unit)

    fun clearTombstone(connection: Connection, path: SemanticGraphSourcePath)

    fun recordTombstone(connection: Connection, path: SemanticGraphSourcePath)

    data object WorkspaceOnly : RepositoryReadAuthority {
        override val publication = RepositoryOverlayPublication.ABSENT

        override fun attachBase(connection: Connection) = RepositoryBaseAttachmentResolution.Attached

        override fun installReadAuthority(connection: Connection) = Unit

        override fun loadInterningAliases(
            connection: Connection,
            pathCodec: PathInterningCodec,
            fqCodec: StringInterningCodec,
        ) = RepositoryInterningAliasResolution.Loaded

        override fun readTable(table: SourceIndexReadTable): SqlReadRelation = SqlReadRelation.primary(table)

        override fun initialize(connection: Connection, incrementGeneration: (Connection) -> Unit) = Unit

        override fun clearTombstone(connection: Connection, path: SemanticGraphSourcePath) = Unit

        override fun recordTombstone(connection: Connection, path: SemanticGraphSourcePath) = Unit
    }

    class WorktreeOverlay(
        private val overlay: ValidatedRepositoryOverlay,
    ) : RepositoryReadAuthority {
        override val publication = RepositoryOverlayPublication.ATTACHED

        override fun attachBase(connection: Connection): RepositoryBaseAttachmentResolution {
            val basePath = overlay.base.path.toJavaPath()
            val uri = "${basePath.toUri().toASCIIString()}?mode=ro&immutable=1".replace("'", "''")
            connection.createStatement().use { statement ->
                statement.execute("ATTACH DATABASE '$uri' AS ${AttachedSqliteDatabase.REPOSITORY_BASE}")
                val observed = statement.executeQuery(
                    "SELECT version FROM ${AttachedSqliteDatabase.REPOSITORY_BASE}.schema_version LIMIT 1",
                ).use { rows ->
                    if (!rows.next()) {
                        RepositoryBaseSchemaObservation.Missing
                    } else if (rows.getInt(1) <= 0) {
                        RepositoryBaseSchemaObservation.Invalid(rows.getInt(1))
                    } else {
                        RepositoryBaseSchemaObservation.Available(SourceIndexSchemaVersion(rows.getInt(1)))
                    }
                }
                val expected = SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION)
                if (observed != RepositoryBaseSchemaObservation.Available(expected)) {
                    return RepositoryBaseAttachmentResolution.Rejected(
                        RepositoryOverlayAuthorityFailure.BaseSchemaRejected(
                            NormalizedPath.ofAbsolute(basePath),
                            expected,
                            observed,
                        ),
                    )
                }
            }
            return RepositoryBaseAttachmentResolution.Attached
        }

        override fun installReadAuthority(connection: Connection) {
            RepositoryOverlaySourceViews.install(connection)
            RepositoryOverlaySemanticViews.install(connection)
        }

        override fun loadInterningAliases(
            connection: Connection,
            pathCodec: PathInterningCodec,
            fqCodec: StringInterningCodec,
        ): RepositoryInterningAliasResolution {
            when (val resolution = pathCodec.loadReadOnlyAliases(connection, AttachedSqliteDatabase.REPOSITORY_BASE)) {
                ReadOnlyInterningAliasResolution.Loaded -> Unit
                is ReadOnlyInterningAliasResolution.Rejected -> return RepositoryInterningAliasResolution.Rejected(
                    RepositoryInterningAliasFailure.PathAliasesRejected(resolution.failure),
                )
            }
            return when (
                val resolution = fqCodec.loadReadOnlyAliases(connection, AttachedSqliteDatabase.REPOSITORY_BASE)
            ) {
                ReadOnlyInterningAliasResolution.Loaded -> RepositoryInterningAliasResolution.Loaded
                is ReadOnlyInterningAliasResolution.Rejected -> RepositoryInterningAliasResolution.Rejected(
                    RepositoryInterningAliasFailure.FqNameAliasesRejected(resolution.failure),
                )
            }
        }

        override fun readTable(table: SourceIndexReadTable): SqlReadRelation =
            SqlReadRelation.repositoryOverlay(table)

        override fun initialize(connection: Connection, incrementGeneration: (Connection) -> Unit) {
            val ownsTransaction = connection.autoCommit
            if (ownsTransaction) connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(
                        """CREATE TABLE IF NOT EXISTS repository_overlay_state (
                            target_snapshot TEXT PRIMARY KEY
                        ) WITHOUT ROWID""",
                    )
                }
                val initialization = connection.prepareStatement(
                    "INSERT OR IGNORE INTO repository_overlay_state(target_snapshot) VALUES (?)",
                ).use { statement ->
                    statement.setString(1, overlay.manifest.target.directoryName.value)
                    if (statement.executeUpdate() == 1) {
                        RepositoryOverlayInitialization.Created
                    } else {
                        RepositoryOverlayInitialization.AlreadyInitialized
                    }
                }
                when (initialization) {
                    RepositoryOverlayInitialization.AlreadyInitialized -> Unit
                    RepositoryOverlayInitialization.Created -> when (seedTombstones(connection)) {
                        RepositoryOverlayTombstoneSeed.Unchanged -> Unit
                        RepositoryOverlayTombstoneSeed.Changed -> incrementGeneration(connection)
                    }
                }
                if (ownsTransaction) connection.commit()
            } catch (failure: Throwable) {
                if (ownsTransaction) connection.rollback()
                throw failure
            } finally {
                if (ownsTransaction) connection.autoCommit = true
            }
        }

        override fun clearTombstone(connection: Connection, path: SemanticGraphSourcePath) {
            connection.prepareStatement("DELETE FROM repository_overlay_tombstones WHERE path = ?").use { statement ->
                statement.setString(1, path.value)
                statement.executeUpdate()
            }
        }

        override fun recordTombstone(connection: Connection, path: SemanticGraphSourcePath) {
            connection.prepareStatement(
                "INSERT OR IGNORE INTO repository_overlay_tombstones(path) VALUES (?)",
            ).use { statement ->
                statement.setString(1, path.value)
                statement.executeUpdate()
            }
        }

        /**
         * Transition: `ValidatedRepositoryOverlay -> RepositoryOverlayTombstoneSeed`.
         *
         * Derives explicit changed/unchanged mutation state from SQLite batch
         * counts; callers never interpret a boolean update protocol.
         */
        private fun seedTombstones(connection: Connection): RepositoryOverlayTombstoneSeed = connection.prepareStatement(
            "INSERT OR IGNORE INTO repository_overlay_tombstones(path) VALUES (?)",
        ).use { statement ->
            (overlay.manifest.tombstones + overlay.manifest.shards.keys).sorted().forEach { path ->
                statement.setString(1, path.value)
                statement.addBatch()
            }
            if (statement.executeBatch().any { updateCount -> updateCount != 0 }) {
                RepositoryOverlayTombstoneSeed.Changed
            } else {
                RepositoryOverlayTombstoneSeed.Unchanged
            }
        }
    }
}

private enum class RepositoryOverlayInitialization {
    AlreadyInitialized,
    Created,
}

private enum class RepositoryOverlayTombstoneSeed {
    Unchanged,
    Changed,
}

internal class RepositoryOverlayState private constructor(
    private val authority: RepositoryReadAuthority,
) {
    val publication: RepositoryOverlayPublication
        get() = authority.publication

    /**
     * Proof transition: `Connection -> RepositoryBaseAttachmentResolution`.
     *
     * Attached proves the selected repository base is attached read-only and
     * exposes the exact current source-index schema. Rejection retains the
     * finite observed-schema or overlay-authority failure. Raw JDBC state is
     * inspected only at this SQLite attachment boundary.
     */
    fun attachBase(connection: Connection): RepositoryBaseAttachmentResolution = authority.attachBase(connection)

    fun installReadAuthority(connection: Connection) = authority.installReadAuthority(connection)

    /**
     * Proof transition:
     * `(Connection, PathInterningCodec, StringInterningCodec) -> RepositoryInterningAliasResolution`.
     *
     * Loaded proves both path and fully-qualified-name aliases from the
     * attached repository authority were admitted into their read namespaces.
     * Rejection is finite [RepositoryInterningAliasFailure] data; raw IDs and
     * strings remain inside the codec/SQLite boundary.
     */
    fun loadInterningAliases(
        connection: Connection,
        pathCodec: PathInterningCodec,
        fqCodec: StringInterningCodec,
    ): RepositoryInterningAliasResolution = authority.loadInterningAliases(connection, pathCodec, fqCodec)

    fun readTable(table: SourceIndexReadTable): SqlReadRelation = authority.readTable(table)

    fun initialize(connection: Connection, incrementGeneration: (Connection) -> Unit) =
        authority.initialize(connection, incrementGeneration)

    fun clearTombstone(connection: Connection, path: SemanticGraphSourcePath) =
        authority.clearTombstone(connection, path)

    fun recordTombstone(connection: Connection, path: SemanticGraphSourcePath) =
        authority.recordTombstone(connection, path)

    companion object {
        /**
         * Proof transition:
         * `(NormalizedPath, WorkspaceRepository) -> RepositoryOverlayStateResolution`.
         *
         * Derives either a closed workspace-only authority or a worktree-overlay
         * authority carrying a regular non-symlink descriptor and an exact,
         * repository-bound, manifest-matched snapshot database. Rejection is
         * finite `RepositoryOverlayAuthorityFailure` data. Raw paths are used
         * only while reading files and attaching SQLite.
         */
        fun resolve(
            databasePath: NormalizedPath,
            repository: WorkspaceRepository,
        ): RepositoryOverlayStateResolution {
            val descriptor = NormalizedPath.ofAbsolute(
                databasePath.toJavaPath().resolveSibling(REPOSITORY_OVERLAY_FILE),
            )
            val descriptorPath = descriptor.toJavaPath()
            if (!Files.exists(descriptorPath, LinkOption.NOFOLLOW_LINKS)) {
                return RepositoryOverlayStateResolution.Resolved(
                    RepositoryOverlayState(RepositoryReadAuthority.WorkspaceOnly),
                )
            }
            if (!Files.isRegularFile(descriptorPath, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(descriptorPath)
            ) {
                return RepositoryOverlayStateResolution.Rejected(
                    RepositoryOverlayAuthorityFailure.DescriptorInvalid(descriptor),
                )
            }
            val manifest = runCatching {
                JSON.decodeFromString<OverlayManifest>(Files.readString(descriptorPath))
            }.getOrElse {
                return RepositoryOverlayStateResolution.Rejected(
                    RepositoryOverlayAuthorityFailure.DescriptorMalformed(descriptor),
                )
            }
            val gitRepository = when (repository) {
                WorkspaceRepository.None -> return RepositoryOverlayStateResolution.Rejected(
                    RepositoryOverlayAuthorityFailure.RepositoryUnavailable(descriptor),
                )
                is WorkspaceRepository.Git -> repository
            }
            val layout = RepositorySnapshotLayout.from(gitRepository.dataDirectory.toJavaPath())
            return when (
                val resolution = layout.resolveDatabase(
                    RepositorySnapshotDatabaseCandidate(manifest.base, manifest.baseDatabase),
                )
            ) {
                is RepositorySnapshotDatabaseResolution.Resolved -> RepositoryOverlayStateResolution.Resolved(
                    RepositoryOverlayState(
                        RepositoryReadAuthority.WorktreeOverlay(
                            ValidatedRepositoryOverlay(descriptor, manifest, resolution.database),
                        ),
                    ),
                )
                is RepositorySnapshotDatabaseResolution.Rejected -> RepositoryOverlayStateResolution.Rejected(
                    RepositoryOverlayAuthorityFailure.SnapshotRejected(descriptor, resolution.failure),
                )
            }
        }

        private val JSON = Json { ignoreUnknownKeys = false }
        private const val REPOSITORY_OVERLAY_FILE = "repository-overlay.json"
    }
}
