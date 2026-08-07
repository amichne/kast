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
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphCommitResult
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.snapshot.ExtractionShardKey
import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.RepositoryRelativePath
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

class NativeSemanticGraphMutationTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `inventory scope reconciliation tombstones graph facts in the same generation`() {
        val sourcePath = SemanticGraphSourcePath.parse("src/A.kt")
        val absolutePath = workspaceRoot.resolve(sourcePath.value).toAbsolutePath().normalize().toString()

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(
                listOf(
                    fileInventoryEntry(
                        workspaceRoot = workspaceRoot,
                        path = absolutePath,
                        lastModifiedMillis = 1,
                        contentHash = FileContentHash.parse("a".repeat(64)),
                        moduleName = ":app[main]",
                        sourceSet = "main",
                    ),
                ),
                FileStageVersions.CURRENT,
            )
            store.replaceSemanticGraphFiles(
                listOf(semanticUpdate(sourcePath, "a", listOf(semanticSymbol("a#source", "source", sourcePath)))),
            )
            val beforeReconciliation = store.readGeneration()

            store.reconcileFileInventory(emptyList(), FileStageVersions.CURRENT)

            assertEquals(beforeReconciliation.value + 1, store.readGeneration().value)
            assertTrue(store.semanticGraphScopeSnapshot().sourcePaths.isEmpty())
            assertTrue(store.readSemanticGraph(listOf(sourcePath)).symbols.isEmpty())
        }
    }

    @Test
    fun `conditional graph replacement rejects a stale generation without mutating rows`() {
        val sourcePath = SemanticGraphSourcePath.parse("src/A.kt")
        val update = semanticUpdate(
            sourcePath,
            "a",
            listOf(semanticSymbol("a#source", "source", sourcePath)),
        )

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            val emptyScope = store.semanticGraphScopeSnapshot()
            val seeded = store.replaceSemanticGraphFilesIfGeneration(
                expectedGeneration = emptyScope.generation,
                updates = listOf(update),
            )
            assertTrue(seeded is SemanticGraphCommitResult.Committed)
            val seededScope = store.semanticGraphScopeSnapshot()
            assertEquals(setOf(sourcePath), seededScope.sourcePaths)

            val staleRemoval = store.replaceSemanticGraphFilesIfGeneration(
                expectedGeneration = emptyScope.generation,
                updates = emptyList(),
                removedPaths = listOf(sourcePath),
            )

            assertTrue(staleRemoval is SemanticGraphCommitResult.GenerationChanged)
            assertEquals(seededScope, store.semanticGraphScopeSnapshot())

            val removal = store.replaceSemanticGraphFilesIfGeneration(
                expectedGeneration = seededScope.generation,
                updates = emptyList(),
                removedPaths = listOf(sourcePath),
            )
            assertTrue(removal is SemanticGraphCommitResult.Committed)
            assertTrue(store.semanticGraphScopeSnapshot().sourcePaths.isEmpty())
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
        val overlay = repositoryOverlay(
            workspaceRoot = workspaceRoot,
            base = snapshotKey('a'),
            target = target,
            tombstones = emptySet(),
            shards = mapOf(
                RepositoryRelativePath.fromCanonical(sourcePath.value) to
                    ExtractionShardKey(target.compatibility, gitObjectId('c')),
            ),
        )
        val database = sourceIndexDatabasePath(workspaceRoot)
        Files.createDirectories(database.parent)
        Files.writeString(
            database.resolveSibling("repository-overlay.json"),
            Json.encodeToString(overlay),
        )

        SqliteSourceIndexStore(overlayWorkspaceIdentity(workspaceRoot, overlay)).use { store ->
            store.ensureSchema()
            store.replaceSemanticGraphFiles(
                listOf(semanticUpdate(sourcePath, "a", listOf(semanticSymbol("a#source", "source", sourcePath)))),
            )
        }
        SqliteSourceIndexStore(overlayWorkspaceIdentity(workspaceRoot, overlay)).use { store -> store.readGeneration() }

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
        val overlay = repositoryOverlay(
            workspaceRoot = workspaceRoot,
            base = snapshotKey('a'),
            target = snapshotKey('b'),
            tombstones = setOf(RepositoryRelativePath.fromCanonical(sourcePath.value)),
            shards = emptyMap(),
        )
        Files.writeString(
            database.resolveSibling("repository-overlay.json"),
            Json.encodeToString(overlay),
        )

        val identity = overlayWorkspaceIdentity(workspaceRoot, overlay)
        val seededGeneration = SqliteSourceIndexStore(identity).use { store -> store.readGeneration() }
        val reopenedGeneration = SqliteSourceIndexStore(identity).use { store -> store.readGeneration() }

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
        val overlay = repositoryOverlay(
            workspaceRoot = workspaceRoot,
            base = snapshotKey('a'),
            target = target,
            tombstones = emptySet(),
            shards = mapOf(
                RepositoryRelativePath.fromCanonical(sourcePath.value) to
                    ExtractionShardKey(target.compatibility, gitObjectId('c')),
            ),
        )
        Files.writeString(
            database.resolveSibling("repository-overlay.json"),
            Json.encodeToString(overlay),
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

        SqliteSourceIndexStore(overlayWorkspaceIdentity(workspaceRoot, overlay)).use { store ->
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

}
