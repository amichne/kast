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

class NativeSemanticGraphSchemaTest {
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

}
