package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.client.WorkspaceRepository
import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphRelation
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationKind
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.reference.DeclarationKind
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.DeclarationVisibility
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
import io.github.amichne.kast.indexstore.snapshot.PublicationEvidence
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotStore
import io.github.amichne.kast.indexstore.snapshot.RepositoryRelativePath
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabaseResolution
import io.github.amichne.kast.indexstore.snapshot.SnapshotCreationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.SnapshotManifest
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class RepositoryOverlayReadAuthorityTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `thin overlay exposes unchanged base facts without copying base rows`() {
        val workspace = root.resolve("workspace").also(Files::createDirectories)
        val unchanged = workspace.resolve("src/Unchanged.kt")
        val changed = workspace.resolve("src/Changed.kt")
        val deleted = workspace.resolve("src/Deleted.kt")
        val unchangedGraphPath = SemanticGraphSourcePath.parse(unchanged.relativeTo(workspace))
        val changedGraphPath = SemanticGraphSourcePath.parse(changed.relativeTo(workspace))
        val unchangedGraphSymbol = semanticSymbol(
            "unchanged-base#symbol",
            "unchanged-base",
            unchangedGraphPath,
        ).copy(annotations = listOf(NonBlankString("sample.AuthoritativeAnnotation")))
        listOf(unchanged, changed, deleted).forEach { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, "class ${path.fileName.toString().removeSuffix(".kt")}")
        }
        val baseKey = snapshotKey('a')
        val targetKey = snapshotKey('b')
        val repositoryDirectory = root.resolve("repositories/${"1".repeat(64)}")
        val stagingDatabase = root.resolve("base-source-index.db")
        SqliteSourceIndexStore(identityFor(workspace, stagingDatabase)).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    sourceUpdate(unchanged, "UnchangedBase"),
                    sourceUpdate(changed, "ChangedBase"),
                    sourceUpdate(deleted, "DeletedBase"),
                ),
                manifest = mapOf(
                    unchanged.toString() to 1L,
                    changed.toString() to 1L,
                    deleted.toString() to 1L,
                ),
            )
            store.replaceDeclarationsFromFiles(
                listOf(
                    unchanged.toString() to listOf(declaration(unchanged, "sample.UnchangedBase")),
                    changed.toString() to listOf(declaration(changed, "sample.ChangedBase")),
                    deleted.toString() to listOf(declaration(deleted, "sample.DeletedBase")),
                ),
            )
            listOf(unchanged, changed, deleted).forEachIndexed { index, path ->
                store.upsertSymbolReference(
                    sourcePath = path.toString(),
                    sourceOffset = index,
                    targetFqName = "sample.Target",
                    targetPath = null,
                    targetOffset = null,
                )
            }
            store.replaceSemanticGraphFiles(
                listOf(
                    semanticUpdate(unchangedGraphPath, "a", listOf(unchangedGraphSymbol)),
                    graphUpdate(changed, "changed-base"),
                    graphUpdate(deleted, "deleted-base"),
                ),
            )
        }
        DriverManager.getConnection("jdbc:sqlite:$stagingDatabase").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """UPDATE file_manifest
                       SET content_hash = '${"a".repeat(64)}', desired_source_version = 'source-1'
                       WHERE filename = 'Unchanged.kt'""",
                )
                statement.execute(
                    """INSERT INTO file_stage_outcomes(
                           prefix_id, filename, stage, content_hash, stage_version,
                           outcome_status, limitations_json, failure_attempt_count
                       )
                       SELECT prefix_id, filename, 'SOURCE', '${"a".repeat(64)}', 'source-1',
                              'COMPLETE', '[]', 0
                       FROM file_manifest WHERE filename = 'Unchanged.kt'""",
                )
            }
        }
        RepositorySnapshotStore(repositoryDirectory).publishMain(
            SnapshotManifest(baseKey, emptyMap(), SnapshotCreationEpochMillis.fromClock(1)),
            NormalizedPath.ofAbsolute(stagingDatabase),
            PublicationEvidence(
                SourceIndexGeneration(1),
                SourceIndexGeneration(1),
                NonNegativeInt(1),
                NonNegativeInt(0),
                NonNegativeInt(0),
                baseKey.treeOid,
                baseKey.indexSchema,
                baseKey.producerVersion,
            ),
        )
        val baseDatabase = when (
            val resolution = RepositorySnapshotStore(repositoryDirectory).resolveSnapshotDatabase(baseKey)
        ) {
            is RepositorySnapshotDatabaseResolution.Resolved -> resolution.database.path
            is RepositorySnapshotDatabaseResolution.Rejected -> error(resolution.failure)
        }
        val overlayDatabase = root.resolve("workspaces/${"2".repeat(64)}/cache/source-index.db")
        Files.createDirectories(overlayDatabase.parent)
        Files.writeString(
            overlayDatabase.resolveSibling("repository-overlay.json"),
            Json.encodeToString(
                OverlayManifest(
                    base = baseKey,
                    target = targetKey,
                    tombstones = setOf(
                        RepositoryRelativePath.fromCanonical(deleted.relativeTo(workspace)),
                        RepositoryRelativePath.fromCanonical(changed.relativeTo(workspace)),
                    ),
                    shards = emptyMap(),
                    baseDatabase = baseDatabase,
                ),
            ),
        )

        SqliteSourceIndexStore(identityFor(workspace, overlayDatabase, repositoryDirectory)).use { store ->
            store.ensureSchema()
            store.saveFileIndex(sourceUpdate(changed, "ChangedMain"))
            store.updateManifestEntry(changed.toString(), 2L)
            store.replaceDeclarationsFromFile(changed.toString(), listOf(declaration(changed, "sample.ChangedMain")))
            store.upsertSymbolReference(
                sourcePath = changed.toString(),
                sourceOffset = 9,
                targetFqName = "sample.Target",
                targetPath = null,
                targetOffset = null,
            )
            val changedGraphSymbol = semanticSymbol("changed-main#symbol", "changed-main", changedGraphPath)
            val boundaryRelation = SemanticGraphRelation(
                sourceKey = changedGraphSymbol.canonicalKey,
                targetKey = unchangedGraphSymbol.canonicalKey,
                kind = SemanticGraphRelationKind.CALLS,
                sourcePath = changedGraphPath,
                startOffset = ByteOffset(0),
                endOffset = ByteOffset(1),
                line = LineNumber(1),
            )
            store.replaceSemanticGraphFiles(
                listOf(
                    semanticUpdate(
                        changedGraphPath,
                        "a",
                        listOf(changedGraphSymbol),
                        boundarySymbols = listOf(unchangedGraphSymbol),
                        relations = listOf(boundaryRelation),
                    ),
                ),
            )

            val source = store.loadSourceIndexSnapshot().candidatePathsByIdentifier
            assertEquals(setOf("UnchangedBase", "ChangedMain"), source.keys)
            assertEquals(
                setOf("sample.UnchangedBase", "sample.ChangedMain"),
                store.searchDeclarations(NonBlankString("sample"), PositiveInt(10)).mapTo(mutableSetOf()) { it.fqName },
            )
            assertEquals(
                setOf(NormalizedPath.of(unchanged).value, NormalizedPath.of(changed).value),
                store.referencesToSymbol("sample.Target").mapTo(mutableSetOf()) { it.sourcePath },
            )
            assertEquals(
                "source-1",
                store.fileStageOutcome(requireNotNull(store.sourcePath(unchanged)), FileIndexStage.SOURCE)?.version?.value,
            )
            assertEquals(
                setOf("unchanged-base", "changed-main"),
                store.readSemanticGraph(
                    listOf(
                        SemanticGraphSourcePath.parse(unchanged.relativeTo(workspace)),
                        SemanticGraphSourcePath.parse(changed.relativeTo(workspace)),
                        SemanticGraphSourcePath.parse(deleted.relativeTo(workspace)),
                    ),
                ).symbols.mapTo(mutableSetOf()) { it.name.value },
            )
            val changedGraph = store.readSemanticGraph(listOf(changedGraphPath))
            assertEquals(listOf(boundaryRelation), changedGraph.relations)
            assertEquals(
                listOf(NonBlankString("sample.AuthoritativeAnnotation")),
                changedGraph.boundarySymbols.single { symbol ->
                    symbol.canonicalKey == unchangedGraphSymbol.canonicalKey
                }.annotations,
            )
            assertEquals(
                mapOf(NormalizedPath.of(unchanged).value to 1L, NormalizedPath.of(changed).value to 2L),
                store.loadManifest(),
            )
        }

        DriverManager.getConnection("jdbc:sqlite:$overlayDatabase").use { connection ->
            assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM main.identifier_paths"))
            assertFalse(
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT identifier FROM main.identifier_paths").use { rows ->
                        check(rows.next())
                        rows.getString(1) == "UnchangedBase"
                    }
                },
            )
        }
    }

    private fun sourceUpdate(path: Path, identifier: String) = FileIndexUpdate(
        path = path.toString(),
        identifiers = setOf(identifier),
        packageName = "sample",
        modulePath = ":app",
        sourceSet = "main",
        imports = emptySet(),
        wildcardImports = emptySet(),
    )

    private fun declaration(path: Path, fqName: String) = DeclarationRow(
        fqName = fqName,
        kind = DeclarationKind.CLASS,
        visibility = DeclarationVisibility.PUBLIC,
        filePath = path.toString(),
        declarationOffset = 0,
        modulePath = ":app",
        sourceSet = "main",
    )

    private fun graphUpdate(path: Path, name: String) =
        SemanticGraphSourcePath.parse(path.relativeTo(root.resolve("workspace"))).let { sourcePath ->
            semanticUpdate(sourcePath, "a", listOf(semanticSymbol("$name#symbol", name, sourcePath)))
        }

    private fun Path.relativeTo(parent: Path): String = parent.relativize(this).joinToString("/")

    private fun identityFor(
        workspace: Path,
        database: Path,
        repositoryDirectory: Path? = null,
    ): WorkspaceIdentity =
        WorkspaceIdentity.fromWorkspaceRoot(workspace).copy(
            repository = repositoryDirectory
                ?.let(NormalizedPath::ofAbsolute)
                ?.let(WorkspaceRepository::Git)
                ?: WorkspaceRepository.None,
            workspaceDataDirectory = NormalizedPath.ofAbsolute(database.parent.parent),
            workspaceCacheDirectory = NormalizedPath.ofAbsolute(database.parent),
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(database),
        )
}
