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

class NativeSemanticGraphScaleTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `enterprise scale scorecard measures ingest incremental size and indexed query p95`() {
        SqliteSourceIndexStore(workspaceRoot).use { store -> store.ensureSchema() }
        val database = sourceIndexDatabasePath(workspaceRoot)

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.autoCommit = false
            val ingestStarted = System.nanoTime()
            connection.prepareStatement(
                """INSERT INTO semantic_files(
                       id, path, package_name, module_name, content_hash, refresh_status, diagnostics_json
                   ) VALUES (?, ?, ?, ?, NULL, 'REFRESHED', '[]')""",
            ).use { statement ->
                repeat(SCALE_FILE_COUNT) { index ->
                    statement.setInt(1, index + 1)
                    statement.setString(2, "src/File$index.kt")
                    statement.setString(3, "scale.p${index % 20}")
                    statement.setString(4, "module-${index % 10}")
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.prepareStatement(
                """INSERT INTO semantic_symbols(
                       id, stable_key, file_id, kind, name, visibility, origin,
                       start_offset, end_offset, line
                   ) VALUES (?, ?, ?, 'FUNCTION', ?, 'PUBLIC', 'SOURCE', 0, 1, 1)""",
            ).use { statement ->
                repeat(SCALE_SYMBOL_COUNT) { index ->
                    statement.setInt(1, index + 1)
                    statement.setString(2, "scale#symbol$index")
                    statement.setInt(3, index % SCALE_FILE_COUNT + 1)
                    statement.setString(4, "symbol$index")
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.prepareStatement(
                """INSERT INTO semantic_edge_occurrences(
                       source_id, target_id, source_file_id, kind, context,
                       start_offset, end_offset, line
                   ) VALUES (?, ?, ?, 'REFERENCES', 'GENERIC_ARG', 0, 1, 1)""",
            ).use { statement ->
                repeat(SCALE_EDGE_COUNT) { index ->
                    val source = index % SCALE_SYMBOL_COUNT + 1
                    statement.setInt(1, source)
                    statement.setInt(2, (source + 97) % SCALE_SYMBOL_COUNT + 1)
                    statement.setInt(3, (source - 1) % SCALE_FILE_COUNT + 1)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.commit()
            val ingestNanos = System.nanoTime() - ingestStarted

            val incrementalStarted = System.nanoTime()
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM semantic_edge_occurrences WHERE source_file_id = 1")
                statement.executeUpdate(
                    """INSERT INTO semantic_edge_occurrences(
                           source_id, target_id, source_file_id, kind, context,
                           start_offset, end_offset, line
                       )
                       SELECT id, (id + 97) % $SCALE_SYMBOL_COUNT + 1, 1,
                              'REFERENCES', 'GENERIC_ARG', 0, 1, 1
                       FROM semantic_symbols
                       WHERE file_id = 1""",
                )
            }
            connection.commit()
            val incrementalNanos = System.nanoTime() - incrementalStarted

            val querySamples = LongArray(21) {
                val started = System.nanoTime()
                connection.prepareStatement(
                    """SELECT id FROM semantic_edge_occurrences
                       WHERE source_id = ? AND kind = 'REFERENCES'
                       ORDER BY target_id LIMIT 100""",
                ).use { statement ->
                    statement.setInt(1, it % SCALE_SYMBOL_COUNT + 1)
                    statement.executeQuery().use { rows -> while (rows.next()) rows.getLong(1) }
                }
                System.nanoTime() - started
            }.sorted()
            val queryP95Nanos = querySamples[(querySamples.size * 95 + 99) / 100 - 1]
            connection.autoCommit = true
            connection.createStatement().use { statement -> statement.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            val databaseBytes = Files.size(database)

            println(
                "nativeGraphScaleMetrics " +
                    "files=$SCALE_FILE_COUNT symbols=$SCALE_SYMBOL_COUNT edges=$SCALE_EDGE_COUNT " +
                    "ingestNanos=$ingestNanos incrementalNanos=$incrementalNanos " +
                    "databaseBytes=$databaseBytes queryP95Nanos=$queryP95Nanos",
            )
            assertTrue(ingestNanos in 1 until 60_000_000_000L)
            assertTrue(incrementalNanos in 1 until 5_000_000_000L)
            assertTrue(databaseBytes > 0)
            assertTrue(queryP95Nanos in 1 until 2_000_000_000L)
        }
    }

}
