package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.client.WorkspaceRepository
import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.result.SemanticGraphRelation
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationKind
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.reference.DeclarationKind
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.DeclarationVisibility
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
import io.github.amichne.kast.indexstore.snapshot.PublicationEvidence
import io.github.amichne.kast.indexstore.snapshot.RepositoryRelativePath
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabaseResolution
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotStore
import io.github.amichne.kast.indexstore.snapshot.SnapshotCreationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.SnapshotManifest
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RepositoryOverlayReplacementAuthorityTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `main replacement excludes stale base annotations edges and supertypes`() {
        val workspace = root.resolve("workspace").also(Files::createDirectories)
        val replacedFile = workspace.resolve("src/Replaced.kt").createSource()
        val targetFile = workspace.resolve("src/Target.kt").createSource()
        val replacedPath = SemanticGraphSourcePath.parse(replacedFile.relativeTo(workspace))
        val targetPath = SemanticGraphSourcePath.parse(targetFile.relativeTo(workspace))
        val replacedBase = semanticSymbol("sample.Replaced#class", "Replaced", replacedPath).copy(
            annotations = listOf(NonBlankString("sample.OldAnnotation")),
        )
        val replacedMain = replacedBase.copy(
            annotations = listOf(NonBlankString("sample.NewAnnotation")),
        )
        val target = semanticSymbol("sample.Target#class", "Target", targetPath)
        val staleBaseRelation = SemanticGraphRelation(
            sourceKey = replacedBase.canonicalKey,
            targetKey = target.canonicalKey,
            kind = SemanticGraphRelationKind.CALLS,
            sourcePath = replacedPath,
            startOffset = ByteOffset(0),
            endOffset = ByteOffset(1),
            line = LineNumber(1),
        )
        val baseKey = snapshotKey('a')
        val targetKey = snapshotKey('b')
        val repositoryDirectory = root.resolve("repositories/${"1".repeat(64)}")
        val stagingDatabase = root.resolve("base-source-index.db")
        SqliteSourceIndexStore(identityFor(workspace, stagingDatabase)).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(sourceUpdate(replacedFile), sourceUpdate(targetFile)),
                manifest = mapOf(replacedFile.toString() to 1L, targetFile.toString() to 1L),
            )
            store.replaceDeclarationsFromFiles(
                listOf(
                    replacedFile.toString() to listOf(
                        declaration(replacedFile, "sample.Replaced", "sample.OldParent"),
                    ),
                    targetFile.toString() to listOf(declaration(targetFile, "sample.Target")),
                ),
            )
            store.replaceSemanticGraphFiles(
                listOf(
                    semanticUpdate(replacedPath, "a", listOf(replacedBase), relations = listOf(staleBaseRelation)),
                    semanticUpdate(targetPath, "b", listOf(target)),
                ),
            )
        }
        RepositorySnapshotStore(repositoryDirectory).publishMain(
            SnapshotManifest(baseKey, emptyMap(), SnapshotCreationEpochMillis.fromClock(1)),
            NormalizedPath.ofAbsolute(stagingDatabase),
            PublicationEvidence(
                SourceIndexGeneration(1),
                SourceIndexGeneration(1),
                NonNegativeInt(2),
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
                    tombstones = setOf(RepositoryRelativePath.fromCanonical(replacedPath.value)),
                    shards = emptyMap(),
                    baseDatabase = baseDatabase,
                ),
            ),
        )

        SqliteSourceIndexStore(identityFor(workspace, overlayDatabase, repositoryDirectory)).use { store ->
            store.ensureSchema()
            store.saveFileIndex(sourceUpdate(replacedFile))
            store.updateManifestEntry(replacedFile.toString(), 2L)
            store.replaceDeclarationsFromFile(
                replacedFile.toString(),
                listOf(declaration(replacedFile, "sample.Replaced", "sample.NewParent")),
            )
            store.replaceSemanticGraphFiles(listOf(semanticUpdate(replacedPath, "c", listOf(replacedMain))))

            val graph = store.readSemanticGraph(listOf(replacedPath, targetPath))
            val effectiveReplacement = graph.symbols.single { it.canonicalKey == replacedMain.canonicalKey }
            assertEquals(listOf(NonBlankString("sample.NewAnnotation")), effectiveReplacement.annotations)
            assertTrue(graph.relations.isEmpty(), "stale base relation survived the overlaid source file")
            assertTrue(store.declarationsWithSupertype("sample.OldParent").isEmpty())
            assertEquals(
                listOf("sample.Replaced"),
                store.declarationsWithSupertype("sample.NewParent").map(DeclarationRow::fqName),
            )
        }
    }

    private fun Path.createSource(): Path = also { path ->
        Files.createDirectories(path.parent)
        Files.writeString(path, "class ${path.fileName.toString().removeSuffix(".kt")}")
    }

    private fun sourceUpdate(path: Path) = FileIndexUpdate(
        path = path.toString(),
        identifiers = setOf(path.fileName.toString().removeSuffix(".kt")),
        packageName = "sample",
        modulePath = ":app",
        sourceSet = "main",
        imports = emptySet(),
        wildcardImports = emptySet(),
    )

    private fun declaration(path: Path, fqName: String, vararg supertypes: String) = DeclarationRow(
        fqName = fqName,
        kind = DeclarationKind.CLASS,
        visibility = DeclarationVisibility.PUBLIC,
        filePath = path.toString(),
        declarationOffset = 0,
        modulePath = ":app",
        sourceSet = "main",
        supertypes = supertypes.toList(),
    )

    private fun identityFor(workspace: Path, database: Path, repositoryDirectory: Path? = null): WorkspaceIdentity =
        WorkspaceIdentity.fromWorkspaceRoot(workspace).copy(
            repository = repositoryDirectory
                ?.let(NormalizedPath::ofAbsolute)
                ?.let(WorkspaceRepository::Git)
                ?: WorkspaceRepository.None,
            workspaceDataDirectory = NormalizedPath.ofAbsolute(database.parent.parent),
            workspaceCacheDirectory = NormalizedPath.ofAbsolute(database.parent),
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(database),
        )

    private fun Path.relativeTo(parent: Path): String = parent.relativize(this).joinToString("/")
}
