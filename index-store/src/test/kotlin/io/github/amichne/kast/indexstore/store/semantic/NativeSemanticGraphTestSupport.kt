package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.result.SemanticGraphFileStatus
import io.github.amichne.kast.api.contract.result.SemanticGraphRelation
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationKind
import io.github.amichne.kast.api.contract.result.SemanticGraphSha256
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbol
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKey
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKind
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.snapshot.ExtractionShardKey
import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
import io.github.amichne.kast.indexstore.snapshot.PublicationEvidence
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotStore
import io.github.amichne.kast.indexstore.snapshot.RepositoryRelativePath
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabaseResolution
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.SnapshotCreationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.SnapshotKey
import io.github.amichne.kast.indexstore.snapshot.SnapshotManifest
import io.github.amichne.kast.indexstore.snapshot.SourceIndexSchemaVersion
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.client.WorkspaceRepository
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

internal fun primaryColumns(connection: Connection, table: String): List<String> =
    connection.createStatement().use { statement ->
        val rows = statement.executeQuery("PRAGMA table_info('$table')")
        buildList {
            while (rows.next()) {
                if (rows.getInt("pk") > 0 || rows.getString("name") == "path") add(rows.getString("name"))
            }
        }
    }

internal fun leadingColumns(connection: Connection, table: String, count: Int): List<String> =
    connection.createStatement().use { statement ->
        val rows = statement.executeQuery("PRAGMA table_info('$table')")
        buildList {
            while (rows.next() && size < count) add(rows.getString("name"))
        }
    }

internal fun assertIndex(
    connection: Connection,
    index: String,
    table: String,
    vararg columns: String,
) {
    val definition = connection.prepareStatement(
        "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ? AND tbl_name = ?",
    ).use { statement ->
        statement.setString(1, index)
        statement.setString(2, table)
        val rows = statement.executeQuery()
        check(rows.next()) { "Missing index $index" }
        rows.getString(1)
    }
    assertTrue(definition.endsWith("(${columns.joinToString()})"), definition)
}

internal fun explain(connection: Connection, sql: String): List<String> =
    connection.createStatement().use { statement ->
        val rows = statement.executeQuery("EXPLAIN QUERY PLAN $sql")
        buildList {
            while (rows.next()) add(rows.getString("detail"))
        }
    }

internal fun scalarLong(connection: Connection, sql: String): Long =
    connection.createStatement().use { statement ->
        val rows = statement.executeQuery(sql)
        check(rows.next())
        rows.getLong(1)
    }

internal fun semanticSymbol(
    key: String,
    name: String,
    path: SemanticGraphSourcePath,
): SemanticGraphSymbol = SemanticGraphSymbol(
    canonicalKey = SemanticGraphSymbolKey.parse(key),
    kind = SemanticGraphSymbolKind.FUNCTION,
    name = NonBlankString(name),
    path = path,
    startOffset = ByteOffset(0),
    endOffset = ByteOffset(1),
    line = LineNumber(1),
)

internal fun semanticUpdate(
    path: SemanticGraphSourcePath,
    hash: String,
    symbols: List<SemanticGraphSymbol>,
    boundarySymbols: List<SemanticGraphSymbol> = emptyList(),
    relations: List<SemanticGraphRelation> = emptyList(),
): SemanticGraphFileIndexUpdate = SemanticGraphFileIndexUpdate(
    path = path,
    packageName = null,
    moduleName = null,
    contentHash = SemanticGraphSha256.parse(hash.repeat(64)),
    status = SemanticGraphFileStatus.REFRESHED,
    diagnostics = emptyList(),
    types = emptyList(),
    symbols = symbols,
    boundarySymbols = boundarySymbols,
    relations = relations,
)

internal fun snapshotKey(character: Char) = SnapshotKey(
    treeOid = gitObjectId(character),
    buildClasspathFingerprint = BuildClasspathFingerprint.fromDigest("d".repeat(64)),
    indexSchema = SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION),
    producerVersion = ProducerVersion.fromVersion("test"),
)

internal fun gitObjectId(character: Char) = GitObjectId.fromCanonical(character.toString().repeat(40))

internal fun repositoryOverlay(
    workspaceRoot: Path,
    base: SnapshotKey,
    target: SnapshotKey,
    tombstones: Set<RepositoryRelativePath>,
    shards: Map<RepositoryRelativePath, ExtractionShardKey>,
): OverlayManifest {
    val repositoryDirectory = workspaceRoot.resolveSibling(
        "${workspaceRoot.fileName}-repository-${base.directoryName.value}",
    )
    val stagingDatabase = repositoryDirectory.resolveSibling("${repositoryDirectory.fileName}-staging.db")
    val identity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).copy(
        workspaceDataDirectory = NormalizedPath.ofAbsolute(stagingDatabase.parent),
        workspaceCacheDirectory = NormalizedPath.ofAbsolute(stagingDatabase.parent),
        sourceIndexDatabasePath = NormalizedPath.ofAbsolute(stagingDatabase),
    )
    SqliteSourceIndexStore(identity).use { store -> store.ensureSchema() }
    val snapshots = RepositorySnapshotStore(repositoryDirectory)
    snapshots.publishMain(
        SnapshotManifest(base, emptyMap(), SnapshotCreationEpochMillis.fromClock(1)),
        NormalizedPath.ofAbsolute(stagingDatabase),
        PublicationEvidence(
            SourceIndexGeneration(1),
            SourceIndexGeneration(1),
            NonNegativeInt(1),
            NonNegativeInt(0),
            NonNegativeInt(0),
            base.treeOid,
            base.indexSchema,
            base.producerVersion,
        ),
    )
    val baseDatabase = when (val resolution = snapshots.resolveSnapshotDatabase(base)) {
        is RepositorySnapshotDatabaseResolution.Resolved -> resolution.database.path
        is RepositorySnapshotDatabaseResolution.Rejected -> error(resolution.failure)
    }
    return OverlayManifest(
        base = base,
        target = target,
        tombstones = tombstones,
        shards = shards,
        baseDatabase = baseDatabase,
    )
}

internal fun overlayWorkspaceIdentity(
    workspaceRoot: Path,
    overlay: OverlayManifest,
): WorkspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).copy(
    repository = WorkspaceRepository.Git(
        NormalizedPath.ofAbsolute(
            checkNotNull(overlay.baseDatabase.toJavaPath().parent?.parent?.parent),
        ),
    ),
)

internal const val SCALE_FILE_COUNT = 200
internal const val SCALE_SYMBOL_COUNT = 10_000
internal const val SCALE_EDGE_COUNT = 50_000
