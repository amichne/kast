package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.RemoteIndexConfig
import io.github.amichne.kast.api.client.fields.IndexingRemoteEnabled
import io.github.amichne.kast.api.client.fields.IndexingRemoteSourceIndexUrl
import io.github.amichne.kast.api.client.fields.OptionalConfigString
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class KastProjectOpenSourceIndexingTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()

        private const val targetSource = """
            package demo

            fun target(): String = "ok"
        """

        private const val callerSource = """
            package demo

            import demo.target

            fun caller(): String = target()
        """
    }

    @TempDir
    lateinit var tempDir: Path

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val targetFileFixture = sourceRootFixture.psiFileFixture("Target.kt", targetSource)
    private val callerFileFixture = sourceRootFixture.psiFileFixture("Caller.kt", callerSource)

    @Test
    fun `project indexer prepopulates SQLite source identifiers and references from IDEA PSI files`() {
        val project = projectFixture.get()
        val targetFile = targetFileFixture.get()
        val callerFile = callerFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(callerFile.virtualFile.path).parent.toAbsolutePath().normalize()
        val callerPath = Path.of(callerFile.virtualFile.path).toAbsolutePath().normalize().toString()
        val targetPath = Path.of(targetFile.virtualFile.path).toAbsolutePath().normalize().toString()
        val diskOnlyPath = workspaceRoot.resolve("unmodeled/DiskOnly.kt")
        Files.createDirectories(diskOnlyPath.parent)
        Files.writeString(diskOnlyPath, "package unmodeled\nclass DiskOnly\n")
        val completeGradleModel = IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
            emptyList(),
            true,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )
        val sourceScans = mutableListOf<String>()
        val relationshipScans = mutableListOf<String>()

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            val indexer = IdeaProjectIndexer(
                project = project,
                workspaceRoot = workspaceRoot,
                store = store,
                cancelled = { false },
                readGradleWorkspaceModel = { completeGradleModel },
                onSourceFileScan = sourceScans::add,
                onRelationshipFileScan = relationshipScans::add,
            )
            indexer.indexProject(KastConfig.defaults())
            val sourceScansAfterFirstRun = sourceScans.toList()
            val relationshipScansAfterFirstRun = relationshipScans.toList()
            assertTrue(sourceScansAfterFirstRun.isNotEmpty())
            assertTrue(relationshipScansAfterFirstRun.isNotEmpty())

            val snapshot = store.loadSourceIndexSnapshot()
            assertEquals(listOf(callerPath), snapshot.candidatePathsByIdentifier.getValue("caller"))
            assertTrue(snapshot.candidatePathsByIdentifier.getValue("target").contains(targetPath))
            assertEquals("demo", snapshot.packageByPath.getValue(callerPath))
            assertEquals(listOf("demo.target"), snapshot.importsByPath.getValue(callerPath))
            assertTrue(store.loadManifest().orEmpty().keys.containsAll(setOf(callerPath, targetPath)))
            assertFalse(store.loadManifest().orEmpty().containsKey(diskOnlyPath.toString()))
            assertTrue(store.referencesToSymbol("demo.target").any { row -> row.sourcePath == callerPath })
            val exactReferences = store.generatedReferencePageToExactSymbol(
                target = ExactReferenceTarget(
                    fqName = "demo.target",
                    declarationFile = NormalizedPath.parse(targetPath),
                    declarationStartOffset = NonNegativeInt(targetFile.text.indexOf("target")),
                ),
                offset = NonNegativeInt(0),
                maxResults = PositiveInt(10),
            )
            assertEquals(2, exactReferences.page.references.size)
            assertTrue(
                exactReferences.page.references.all { reference -> reference.sourcePath == callerPath },
                "reference target offsets must use the same name identity returned by exact symbol resolution",
            )
            val lookup = ReferenceIndexLookup { target, offset, maxResults ->
                val generated = store.generatedReferencePageToExactSymbol(target, offset, maxResults)
                if (generated.exactIdentityAvailable) {
                    IndexedReferenceLookupResult.Ready(generated.page, generated.generation)
                } else {
                    IndexedReferenceLookupResult.IdentityUnavailable(generated.generation)
                }
            }
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 500,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                referenceIndexLookup = lookup,
                relationshipCoverageAuthority = RelationshipCoverageAuthority.proven(),
            ).use { backend ->
                val result = runBlocking {
                    backend.findReferences(
                        ReferencesQuery(
                            position = FilePosition(targetPath, targetFile.text.indexOf("target")),
                            maxResults = 10,
                        ),
                    )
                }
                assertEquals(ResultCardinality.Exact(2), result.cardinality)
                assertEquals(2, result.references.size)
                assertEquals(true, result.searchScope?.exhaustive)
            }

            indexer.indexProject(KastConfig.defaults())
            assertEquals(sourceScansAfterFirstRun, sourceScans, "unchanged source files must not be rescanned")
            assertEquals(
                relationshipScansAfterFirstRun,
                relationshipScans,
                "unchanged relationship files must not be rescanned",
            )
        }
    }

    @Test
    fun `remote source index hydration copies configured snapshot before local indexing opens the store`() {
        val remoteWorkspaceRoot = tempDir.resolve("remote-workspace")
        val localWorkspaceRoot = tempDir.resolve("local-workspace")
        val remoteFile = remoteWorkspaceRoot.resolve("src/Remote.kt").toAbsolutePath().normalize().toString()
        val remoteDbPath = sourceIndexDatabasePath(remoteWorkspaceRoot)
        SqliteSourceIndexStore(remoteWorkspaceRoot).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    FileIndexUpdate(
                        path = remoteFile,
                        identifiers = setOf("RemoteIndexed"),
                        packageName = "remote",
                        modulePath = ":remote",
                        sourceSet = "main",
                        imports = emptySet(),
                        wildcardImports = emptySet(),
                    ),
                ),
                manifest = mapOf(remoteFile to 1L),
            )
        }
        checkpointSqliteDatabase(remoteDbPath)

        val hydrated = SourceIndexHydrator().hydrate(
            workspaceRoot = localWorkspaceRoot,
            remote = RemoteIndexConfig(
                enabled = IndexingRemoteEnabled(true),
                sourceIndexUrl = IndexingRemoteSourceIndexUrl(OptionalConfigString(remoteDbPath.toUri().toString())),
            ),
        )

        assertTrue(hydrated)
        SqliteSourceIndexStore(localWorkspaceRoot).use { store ->
            val snapshot = store.loadSourceIndexSnapshot()
            val hydratedFile = localWorkspaceRoot.resolve("src/Remote.kt").toAbsolutePath().normalize().toString()
            assertEquals(listOf(hydratedFile), snapshot.candidatePathsByIdentifier.getValue("RemoteIndexed"))
        }
    }

    private fun checkpointSqliteDatabase(dbPath: Path) {
        java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { statement -> statement.execute("PRAGMA wal_checkpoint(FULL)") }
        }
        Files.deleteIfExists(Path.of("$dbPath-wal"))
        Files.deleteIfExists(Path.of("$dbPath-shm"))
    }
}
