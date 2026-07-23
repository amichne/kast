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

class NativeSemanticGraphStoreTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `schema stores canonical graph facts under numeric identities with required indexes`() {
        SqliteSourceIndexStore(workspaceRoot).use { store -> store.ensureSchema() }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(workspaceRoot)}").use { connection ->
            assertTrue(SOURCE_INDEX_SCHEMA_VERSION > 9)
            assertEquals(listOf("id", "path"), primaryColumns(connection, "semantic_files"))
            assertEquals(
                listOf("id", "stable_key", "file_id", "owner_id"),
                leadingColumns(connection, "semantic_symbols", 4),
            )
            assertEquals(
                listOf("id", "source_id", "target_id", "source_file_id", "kind", "context"),
                leadingColumns(connection, "semantic_edge_occurrences", 6),
            )
            assertIndex(connection, "idx_semantic_symbols_file_id_id", "semantic_symbols", "file_id", "id")
            assertIndex(connection, "idx_semantic_symbols_owner_id_id", "semantic_symbols", "owner_id", "id")
            assertIndex(
                connection,
                "idx_semantic_edges_source_file_id_id",
                "semantic_edge_occurrences",
                "source_file_id",
                "id",
            )
            assertIndex(
                connection,
                "idx_semantic_edges_source_kind_target",
                "semantic_edge_occurrences",
                "source_id",
                "kind",
                "target_id",
            )
            assertIndex(
                connection,
                "idx_semantic_edges_target_kind_source",
                "semantic_edge_occurrences",
                "target_id",
                "kind",
                "source_id",
            )
        }
    }

    @Test
    fun `scoped keyset and boundary reads use indexed searches`() {
        SqliteSourceIndexStore(workspaceRoot).use { store -> store.ensureSchema() }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(workspaceRoot)}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TEMP TABLE requested_semantic_file_ids(id INTEGER PRIMARY KEY) WITHOUT ROWID",
                )
            }
            val scopedPlan = explain(
                connection,
                """SELECT symbols.id
                   FROM requested_semantic_file_ids requested
                   JOIN semantic_symbols symbols ON symbols.file_id = requested.id
                   WHERE symbols.id > 0
                   ORDER BY symbols.id
                   LIMIT 100""",
            )
            val boundaryPlan = explain(
                connection,
                """SELECT DISTINCT target.id
                   FROM semantic_edge_occurrences edges INDEXED BY idx_semantic_edges_source_file_id_id
                   JOIN semantic_symbols target ON target.id = edges.target_id
                   LEFT JOIN requested_semantic_file_ids internal ON internal.id = target.file_id
                   WHERE edges.source_file_id IN (SELECT id FROM requested_semantic_file_ids)
                     AND internal.id IS NULL""",
            )

            assertTrue(scopedPlan.any { it.contains("SEARCH symbols USING") }, scopedPlan.joinToString())
            assertTrue(boundaryPlan.any { it.contains("SEARCH edges USING") }, boundaryPlan.joinToString())
            assertTrue(boundaryPlan.none { it.contains("SCAN semantic_symbols") }, boundaryPlan.joinToString())
            assertTrue(boundaryPlan.none { it.contains("SCAN semantic_edge_occurrences") }, boundaryPlan.joinToString())
        }
    }

    @Test
    fun `file package and module quotients conserve canonical edge occurrences`() {
        SqliteSourceIndexStore(workspaceRoot).use { store -> store.ensureSchema() }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(workspaceRoot)}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """INSERT INTO semantic_files(
                           id, path, package_name, module_name, content_hash, refresh_status, diagnostics_json
                       ) VALUES
                           (1, 'A.kt', 'alpha', 'app', NULL, 'REFRESHED', '[]'),
                           (2, 'B.kt', 'beta', 'lib', NULL, 'REFRESHED', '[]')""",
                )
                statement.execute(
                    """INSERT INTO semantic_symbols(
                           id, stable_key, file_id, kind, name, visibility, origin,
                           start_offset, end_offset, line
                       ) VALUES
                           (1, 'a#one', 1, 'FUNCTION', 'one', 'PUBLIC', 'SOURCE', 0, 1, 1),
                           (2, 'a#two', 1, 'FUNCTION', 'two', 'PUBLIC', 'SOURCE', 2, 3, 1),
                           (3, 'b#target', 2, 'CLASS', 'Target', 'PUBLIC', 'SOURCE', 0, 1, 1)""",
                )
                statement.execute(
                    """INSERT INTO semantic_edge_occurrences(
                           source_id, target_id, source_file_id, kind, context,
                           start_offset, end_offset, line
                       ) VALUES
                           (1, 3, 1, 'CALLS', 'NONE', 0, 1, 1),
                           (1, 3, 1, 'CALLS', 'NONE', 2, 3, 1),
                           (2, 3, 1, 'CALLS', 'NONE', 4, 5, 1)""",
                )
            }

            val occurrences = scalarLong(connection, "SELECT COUNT(*) FROM semantic_edge_occurrences")
            listOf(
                "semantic_file_quotient",
                "semantic_package_quotient",
                "semantic_module_quotient",
            ).forEach { view ->
                assertEquals(occurrences, scalarLong(connection, "SELECT COALESCE(SUM(weight), 0) FROM $view"))
            }
        }
    }

    @Test
    fun `target refresh preserves inbound edges only to surviving symbols`() {
        val sourcePath = SemanticGraphSourcePath.parse("src/A.kt")
        val targetPath = SemanticGraphSourcePath.parse("src/B.kt")
        val source = semanticSymbol("a#source", "source", sourcePath)
        val target = semanticSymbol("b#target", "target", targetPath)
        val relation = SemanticGraphRelation(
            sourceKey = source.canonicalKey,
            targetKey = target.canonicalKey,
            kind = SemanticGraphRelationKind.CALLS,
            sourcePath = sourcePath,
            startOffset = ByteOffset(0),
            endOffset = ByteOffset(1),
            line = LineNumber(1),
        )
        val sourceUpdate = semanticUpdate(
            path = sourcePath,
            hash = "a",
            symbols = listOf(source),
            boundarySymbols = listOf(target),
            relations = listOf(relation),
        )
        val targetUpdate = semanticUpdate(targetPath, "b", listOf(target))

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.replaceSemanticGraphFiles(listOf(sourceUpdate, targetUpdate))
            store.replaceSemanticGraphFiles(listOf(targetUpdate.copy(contentHash = SemanticGraphSha256.parse("c".repeat(64)))))

            assertEquals(listOf(relation), store.readSemanticGraph(listOf(sourcePath)).relations)

            store.replaceSemanticGraphFiles(
                listOf(
                    targetUpdate.copy(
                        contentHash = SemanticGraphSha256.parse("d".repeat(64)),
                        symbols = emptyList(),
                    ),
                ),
            )

            assertTrue(store.readSemanticGraph(listOf(sourcePath)).relations.isEmpty())
        }
    }

    @Test
    fun `reopening an overlay keeps refreshed shard tombstones cleared`() {
        val sourcePath = SemanticGraphSourcePath.parse("src/A.kt")
        val target = snapshotKey('b')
        val overlay = OverlayManifest(
            base = snapshotKey('a'),
            target = target,
            tombstones = emptySet(),
            shards = mapOf(
                sourcePath.value to ExtractionShardKey(target.compatibility, gitObjectId('c')),
            ),
        )
        val database = sourceIndexDatabasePath(workspaceRoot)
        Files.createDirectories(database.parent)
        Files.writeString(
            database.resolveSibling("repository-overlay.json"),
            Json.encodeToString(overlay),
        )

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.replaceSemanticGraphFiles(
                listOf(semanticUpdate(sourcePath, "a", listOf(semanticSymbol("a#source", "source", sourcePath)))),
            )
        }
        SqliteSourceIndexStore(workspaceRoot).use { store -> store.readGeneration() }

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            assertEquals(
                0,
                scalarLong(
                    connection,
                    "SELECT COUNT(*) FROM repository_overlay_tombstones WHERE path = '${sourcePath.value}'",
                ),
            )
        }
    }

    @Test
    fun `first overlay seed advances generation when graph-visible state changes`() {
        val sourcePath = SemanticGraphSourcePath.parse("src/A.kt")
        val baseGeneration = SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.replaceSemanticGraphFiles(
                listOf(semanticUpdate(sourcePath, "a", listOf(semanticSymbol("a#source", "source", sourcePath)))),
            ).generation
        }
        val database = sourceIndexDatabasePath(workspaceRoot)
        Files.writeString(
            database.resolveSibling("repository-overlay.json"),
            Json.encodeToString(
                OverlayManifest(
                    base = snapshotKey('a'),
                    target = snapshotKey('b'),
                    tombstones = setOf(sourcePath.value),
                    shards = emptyMap(),
                ),
            ),
        )

        val seededGeneration = SqliteSourceIndexStore(workspaceRoot).use { store -> store.readGeneration() }
        val reopenedGeneration = SqliteSourceIndexStore(workspaceRoot).use { store -> store.readGeneration() }

        assertEquals(
            listOf(baseGeneration.value + 1, baseGeneration.value + 1),
            listOf(seededGeneration.value, reopenedGeneration.value),
        )
    }

    @Test
    fun `schema mismatch overlay rebuilds before tombstone seeding`() {
        val sourcePath = SemanticGraphSourcePath.parse("src/A.kt")
        val target = snapshotKey('b')
        val database = sourceIndexDatabasePath(workspaceRoot)
        Files.createDirectories(database.parent)
        Files.writeString(
            database.resolveSibling("repository-overlay.json"),
            Json.encodeToString(
                OverlayManifest(
                    base = snapshotKey('a'),
                    target = target,
                    tombstones = emptySet(),
                    shards = mapOf(
                        sourcePath.value to ExtractionShardKey(target.compatibility, gitObjectId('c')),
                    ),
                ),
            ),
        )
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE schema_version(version INTEGER NOT NULL, generation INTEGER NOT NULL)",
                )
                statement.execute(
                    "INSERT INTO schema_version(version, generation) VALUES (${SOURCE_INDEX_SCHEMA_VERSION - 1}, 0)",
                )
            }
        }

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            assertFalse(store.ensureSchema())
        }

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            assertEquals(
                SOURCE_INDEX_SCHEMA_VERSION.toLong(),
                scalarLong(connection, "SELECT version FROM schema_version"),
            )
            assertEquals(
                1,
                scalarLong(
                    connection,
                    "SELECT COUNT(*) FROM repository_overlay_tombstones WHERE path = '${sourcePath.value}'",
                ),
            )
        }
    }

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

    private fun primaryColumns(connection: Connection, table: String): List<String> =
        connection.createStatement().use { statement ->
            val rows = statement.executeQuery("PRAGMA table_info('$table')")
            buildList {
                while (rows.next()) {
                    if (rows.getInt("pk") > 0 || rows.getString("name") == "path") add(rows.getString("name"))
                }
            }
        }

    private fun leadingColumns(connection: Connection, table: String, count: Int): List<String> =
        connection.createStatement().use { statement ->
            val rows = statement.executeQuery("PRAGMA table_info('$table')")
            buildList {
                while (rows.next() && size < count) add(rows.getString("name"))
            }
        }

    private fun assertIndex(
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

    private fun explain(connection: Connection, sql: String): List<String> =
        connection.createStatement().use { statement ->
            val rows = statement.executeQuery("EXPLAIN QUERY PLAN $sql")
            buildList {
                while (rows.next()) add(rows.getString("detail"))
            }
        }

    private fun scalarLong(connection: Connection, sql: String): Long =
        connection.createStatement().use { statement ->
            val rows = statement.executeQuery(sql)
            check(rows.next())
            rows.getLong(1)
        }

    private fun semanticSymbol(
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

    private fun semanticUpdate(
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

    private fun snapshotKey(character: Char) = SnapshotKey(
        treeOid = gitObjectId(character),
        buildClasspathFingerprint = BuildClasspathFingerprint.parse("d".repeat(64)),
        indexSchema = SOURCE_INDEX_SCHEMA_VERSION,
        producerVersion = ProducerVersion.parse("test"),
    )

    private fun gitObjectId(character: Char) = GitObjectId.parse(character.toString().repeat(40))

    private companion object {
        const val SCALE_FILE_COUNT = 200
        const val SCALE_SYMBOL_COUNT = 10_000
        const val SCALE_EDGE_COUNT = 50_000
    }
}
