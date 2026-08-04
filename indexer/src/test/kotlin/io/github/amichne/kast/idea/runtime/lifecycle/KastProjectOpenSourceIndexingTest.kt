package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.RelationshipIndexingEnabled
import io.github.amichne.kast.api.client.RemoteIndexConfig
import io.github.amichne.kast.api.client.WorkspaceIdentity
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
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class KastProjectOpenSourceIndexingTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture(openAfterCreation = true)

        private const val targetSource = """
            package demo

            fun target(): String = "ok"
        """

        private const val callerSource = """
            package demo

            import demo.target

            fun caller(): String = target()
        """

        private const val failingSource = """
            package demo

            fun unavailable(): String = "unavailable"
        """
    }

    @TempDir
    lateinit var tempDir: Path

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val targetFileFixture = sourceRootFixture.psiFileFixture("Target.kt", targetSource)
    private val callerFileFixture = sourceRootFixture.psiFileFixture("Caller.kt", callerSource)
    private val failingFileFixture = sourceRootFixture.psiFileFixture("Unavailable.kt", failingSource)

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
        val pipelineEvents = mutableListOf<String>()

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            val indexer = IdeaProjectIndexer(
                project = project,
                workspaceRoot = workspaceRoot,
                store = store,
                cancelled = { false },
                readGradleWorkspaceModel = { completeGradleModel },
                onSourceFileScan = sourceScans::add,
                onRelationshipFileScan = { path ->
                    relationshipScans.add(path)
                    pipelineEvents.add("reference")
                },
            )
            indexer.indexProject(KastConfig.defaults()) { scope ->
                assertTrue(scope.paths.map { it.rawPath }.containsAll(listOf(callerPath, targetPath)))
                pipelineEvents.add("graph")
            }
            assertTrue(sourceScans.isNotEmpty())
            assertTrue(relationshipScans.isNotEmpty())
            assertTrue(pipelineEvents.indexOf("graph") < pipelineEvents.indexOf("reference"))

            val snapshot = store.loadSourceIndexSnapshot()
            val callerSourcePath = workspaceSourcePath(workspaceRoot, callerPath)
            val targetSourcePath = workspaceSourcePath(workspaceRoot, targetPath)
            assertEquals(listOf(callerSourcePath), snapshot.candidatePathsByIdentifier.getValue("caller"))
            assertTrue(snapshot.candidatePathsByIdentifier.getValue("target").contains(targetSourcePath))
            assertEquals("demo", snapshot.packageByPath.getValue(callerSourcePath))
            assertEquals(listOf("demo.target"), snapshot.importsByPath.getValue(callerSourcePath))
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
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 500,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                referenceIndexLookup = lookup,
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
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
        }

        val restartedSourceScans = mutableListOf<String>()
        val restartedRelationshipScans = mutableListOf<String>()
        SqliteSourceIndexStore(workspaceRoot).use { reopened ->
            IdeaProjectIndexer(
                project = project,
                workspaceRoot = workspaceRoot,
                store = reopened,
                cancelled = { false },
                readGradleWorkspaceModel = { completeGradleModel },
                onSourceFileScan = restartedSourceScans::add,
                onRelationshipFileScan = restartedRelationshipScans::add,
            ).indexProject(KastConfig.defaults())
            assertTrue(restartedSourceScans.isEmpty(), "unchanged source files must not be rescanned after restart")
            assertTrue(
                restartedRelationshipScans.isEmpty(),
                "unchanged relationship files must not be rescanned after restart",
            )
        }
    }

    @Test
    fun `source scan rejects facts from a newer PSI revision`() {
        assertChangedPsiDoesNotCommit(FileIndexStage.SOURCE)
    }

    @Test
    fun `relationship scan rejects facts from a newer PSI revision`() {
        assertChangedPsiDoesNotCommit(FileIndexStage.RELATIONSHIPS)
    }

    @Test
    fun `focused relationship refresh returns a durable eligible failure`() {
        val project = projectFixture.get()
        val failingFile = failingFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(failingFile.virtualFile.path).parent.toAbsolutePath().normalize()
        val failingPath = failingFile.virtualFile.path
        val workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).copy(
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(tempDir.resolve("focused-failure.db")),
        )
        val completeGradleModel = IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
            emptyList(),
            true,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )

        SqliteSourceIndexStore(workspaceIdentity).use { store ->
            val indexer = IdeaProjectIndexer(
                project = project,
                workspaceRoot = workspaceRoot,
                store = store,
                cancelled = { false },
                workspaceIdentity = workspaceIdentity,
                readGradleWorkspaceModel = { completeGradleModel },
                onRelationshipFileScan = { path ->
                    if (path == failingPath) {
                        val application = ApplicationManager.getApplication()
                        application.invokeAndWait {
                            application.runWriteAction {
                                if (failingFile.virtualFile.isValid) failingFile.virtualFile.delete(this)
                            }
                        }
                    }
                },
            )
            val defaults = KastConfig.defaults()
            indexer.indexProject(
                defaults.copy(
                    indexing = defaults.indexing.copy(
                        relationships = defaults.indexing.relationships.copy(
                            enabled = RelationshipIndexingEnabled(false),
                        ),
                    ),
                ),
            )

            val outcomes = indexer.refreshSymbolRelationships(
                workspaceSourcePaths(workspaceRoot, listOf(failingPath)),
            )

            val outcome = outcomes.single()
            assertEquals(failingPath, outcome.path.rawPath)
            assertEquals(FileStageOutcomeStatus.FAILED, outcome.status)
            assertEquals(FileStageFailureCode.PSI_UNAVAILABLE, requireNotNull(outcome.failure).code)
            assertEquals(
                outcome,
                store.fileStageOutcome(failingPath, FileIndexStage.RELATIONSHIPS),
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
            assertEquals(
                listOf(workspaceSourcePath(localWorkspaceRoot, hydratedFile)),
                snapshot.candidatePathsByIdentifier.getValue("RemoteIndexed"),
            )
        }
    }

    private fun checkpointSqliteDatabase(dbPath: Path) {
        java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { statement -> statement.execute("PRAGMA wal_checkpoint(FULL)") }
        }
        Files.deleteIfExists(Path.of("$dbPath-wal"))
        Files.deleteIfExists(Path.of("$dbPath-shm"))
    }

    private fun assertChangedPsiDoesNotCommit(stage: FileIndexStage) {
        val project = projectFixture.get()
        val callerFile = callerFileFixture.get()
        targetFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(callerFile.virtualFile.path).parent.toAbsolutePath().normalize()
        val callerPath = Path.of(callerFile.virtualFile.path).toAbsolutePath().normalize().toString()
        val targetPath = Path.of(targetFileFixture.get().virtualFile.path).toAbsolutePath().normalize().toString()
        val document = runIdeaReadAction {
            FileDocumentManager.getInstance().getDocument(callerFile.virtualFile)!!
        }
        val workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).copy(
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(
                tempDir.resolve("changed-${stage.name.lowercase()}.db"),
            ),
        )
        val completeGradleModel = IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
            emptyList(),
            true,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )
        var changed = false
        val changeCaller: (String) -> Unit = { path ->
            if (!changed && path == callerPath) {
                changed = true
                replaceDocument(project, document, callerSource.replace("caller", "changedCaller"))
            }
        }

        replaceDocument(project, document, callerSource)
        try {
            SqliteSourceIndexStore(workspaceIdentity).use { store ->
                IdeaProjectIndexer(
                    project = project,
                    workspaceRoot = workspaceRoot,
                    store = store,
                    cancelled = { false },
                    workspaceIdentity = workspaceIdentity,
                    readGradleWorkspaceModel = { completeGradleModel },
                    onSourceFileScan = if (stage == FileIndexStage.SOURCE) changeCaller else { _ -> },
                    onRelationshipFileScan = if (stage == FileIndexStage.RELATIONSHIPS) changeCaller else { _ -> },
                ).indexProject(KastConfig.defaults())

                assertNull(store.fileStageOutcome(callerPath, stage))
                assertNotNull(store.fileStageOutcome(targetPath, stage))
                assertTrue(
                    store.pendingFileStages(stage).any { work -> work.path.rawPath == callerPath },
                    "A scan from a newer PSI revision must remain pending",
                )
            }
        } finally {
            replaceDocument(project, document, callerSource)
        }
    }

    private fun replaceDocument(
        project: Project,
        document: com.intellij.openapi.editor.Document,
        content: String,
    ) {
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction { document.setText(content) }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}
