package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
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
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.SnapshotKey
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
    buildClasspathFingerprint = BuildClasspathFingerprint.parse("d".repeat(64)),
    indexSchema = SOURCE_INDEX_SCHEMA_VERSION,
    producerVersion = ProducerVersion.parse("test"),
)

internal fun gitObjectId(character: Char) = GitObjectId.parse(character.toString().repeat(40))

internal const val SCALE_FILE_COUNT = 200
internal const val SCALE_SYMBOL_COUNT = 10_000
internal const val SCALE_EDGE_COUNT = 50_000
