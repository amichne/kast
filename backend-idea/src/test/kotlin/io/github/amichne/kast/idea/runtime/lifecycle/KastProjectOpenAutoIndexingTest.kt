package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastPluginBackend

import io.github.amichne.kast.api.client.fields.OptionalConfigString
import io.github.amichne.kast.api.client.fields.IdeaBackendEnabled
import io.github.amichne.kast.api.client.fields.IndexingRemoteSourceIndexUrl
import io.github.amichne.kast.api.client.fields.IndexingRemoteEnabled
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.ProjectOpenConfig
import io.github.amichne.kast.api.client.RemoteIndexConfig
import io.github.amichne.kast.api.client.fields.ProjectOpenAutoExcludeGit
import io.github.amichne.kast.api.client.fields.ProjectOpenGradleLoadEnabled
import io.github.amichne.kast.api.client.fields.ProjectOpenProfile
import io.github.amichne.kast.api.client.fields.ProjectOpenProfileAutoInit
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class KastProjectOpenAutoIndexingTest {
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
    fun `project open reports indexing then waits for Gradle before starting reference index`() {
        val project = projectFixture.get()
        var loadedGradleWorkspaceRoot: Path? = null
        var startedProject: Project? = null
        var gradleCompletion: ((Throwable?) -> Unit)? = null
        val events = mutableListOf<String>()
        val config = KastConfig.defaults()

        val started = KastProjectOpenAutoIndexing.execute(
            project = project,
            config = config,
            loadGradleProject = { workspaceRoot, startupConfig, onComplete ->
                assertSame(config, startupConfig)
                events.add("gradle")
                loadedGradleWorkspaceRoot = workspaceRoot
                gradleCompletion = onComplete
                ProjectOpenGradleLoadResult.Requested(GradleProjectLoadRequest.Link(workspaceRoot))
            },
            startBackend = { _, startupConfig ->
                assertSame(config, startupConfig)
                events.add("backend")
                startedProject = project
            },
            startReferenceIndex = { events.add("index") },
            failReadiness = { _, error -> events.add("failed:${error.message}") },
        )

        assertTrue(started)
        assertSame(project, startedProject)
        assertEquals(listOf("backend", "gradle"), events)
        gradleCompletion?.invoke(null)
        assertEquals(listOf("backend", "gradle", "index"), events)
        assertNotNull(loadedGradleWorkspaceRoot)
        assertEquals(loadedGradleWorkspaceRoot, loadedGradleWorkspaceRoot?.toAbsolutePath()?.normalize())
    }

    @Test
    fun `Gradle project load request schedules background work`() {
        val project = projectFixture.get()
        Files.writeString(tempDir.resolve("settings.gradle.kts"), "pluginManagement {}\n")
        var scheduled = false

        val result = KastProjectOpenGradleLoad.execute(
            project = project,
            workspaceRoot = tempDir,
            enabled = ProjectOpenGradleLoadEnabled(true),
            scheduleGradleLoad = {
                scheduled = true
            },
        )

        assertEquals(
            ProjectOpenGradleLoadResult.Requested(
                GradleProjectLoadRequest.Link(tempDir.toAbsolutePath().normalize()),
            ),
            result,
        )
        assertTrue(scheduled)
    }

    @Test
    fun `Gradle project load refreshes a linked project whose imported model is incomplete`() {
        val project = projectFixture.get()
        val linkedWorkspaceRoot = tempDir.toAbsolutePath().normalize()
        Files.writeString(linkedWorkspaceRoot.resolve("settings.gradle.kts"), "pluginManagement {}\n")
        val gradleSettings = GradleSettings.getInstance(project)
        val previousSettings = gradleSettings.linkedProjectsSettings.toList()
        var scheduled = false

        try {
            gradleSettings.setLinkedProjectsSettings(
                listOf(
                    GradleProjectSettings().apply {
                        externalProjectPath = linkedWorkspaceRoot.toString()
                    },
                ),
            )

            val result = KastProjectOpenGradleLoad.execute(
                project = project,
                workspaceRoot = linkedWorkspaceRoot,
                enabled = ProjectOpenGradleLoadEnabled(true),
                scheduleGradleLoad = {
                    scheduled = true
                },
            )

            assertEquals(
                ProjectOpenGradleLoadResult.Requested(
                    GradleProjectLoadRequest.Refresh(linkedWorkspaceRoot),
                ),
                result,
            )
            assertTrue(scheduled)
        } finally {
            gradleSettings.setLinkedProjectsSettings(previousSettings)
        }
    }

    @Test
    fun `Gradle project load skips a linked project whose imported model is complete`() {
        val project = projectFixture.get()
        val linkedWorkspaceRoot = tempDir.toAbsolutePath().normalize()
        Files.writeString(linkedWorkspaceRoot.resolve("settings.gradle.kts"), "pluginManagement {}\n")
        val gradleSettings = GradleSettings.getInstance(project)
        val previousSettings = gradleSettings.linkedProjectsSettings.toList()
        var scheduled = false

        try {
            gradleSettings.setLinkedProjectsSettings(
                listOf(
                    GradleProjectSettings().apply {
                        externalProjectPath = linkedWorkspaceRoot.toString()
                    },
                ),
            )

            val result = KastProjectOpenGradleLoad.execute(
                project = project,
                workspaceRoot = linkedWorkspaceRoot,
                enabled = ProjectOpenGradleLoadEnabled(true),
                isImportedGradleModelComplete = { _, _ -> true },
                scheduleGradleLoad = {
                    scheduled = true
                },
            )

            assertEquals(
                ProjectOpenGradleLoadResult.Skipped("already loaded"),
                result,
            )
            assertFalse(scheduled)
        } finally {
            gradleSettings.setLinkedProjectsSettings(previousSettings)
        }
    }

    @Test
    fun `project open skips Gradle load when project open Gradle load is disabled`() {
        val project = projectFixture.get()
        var requestedGradleLoad = false
        var startedProject: Project? = null
        val disabledConfig = KastConfig.defaults().copy(
            projectOpen = ProjectOpenConfig(
                profileAutoInit = ProjectOpenProfileAutoInit(true),
                profile = ProjectOpenProfile(ProjectOpenProfile.JETBRAINS_PLUGIN),
                autoExcludeGit = ProjectOpenAutoExcludeGit(true),
                gradleLoadEnabled = ProjectOpenGradleLoadEnabled(false),
            ),
        )

        val started = KastProjectOpenAutoIndexing.execute(
            project = project,
            config = disabledConfig,
            loadGradleProject = { workspaceRoot, config, onComplete ->
                requestedGradleLoad = true
                KastProjectOpenGradleLoad.execute(
                    project,
                    workspaceRoot,
                    config.projectOpen.gradleLoadEnabled,
                    onComplete,
                )
            },
            startBackend = { startupProject, _ -> startedProject = startupProject },
            startReferenceIndex = { startedProject = it },
            failReadiness = { _, _ -> },
        )

        assertTrue(started)
        assertFalse(requestedGradleLoad)
        assertSame(project, startedProject)
    }

    @Test
    fun `project open skips backend and reference indexing when idea backend is disabled`() {
        val project = projectFixture.get()
        var started = false
        val disabledConfig = KastConfig.defaults().let { config ->
            config.copy(
                backends = config.backends.copy(
                    idea = config.backends.idea.copy(enabled = IdeaBackendEnabled(false)),
                ),
            )
        }

        val requestedStart = KastProjectOpenAutoIndexing.execute(
            project = project,
            config = disabledConfig,
            startBackend = { _, _ -> started = true },
            startReferenceIndex = { started = true },
            failReadiness = { _, _ -> },
        )

        assertFalse(requestedStart)
        assertFalse(started)
    }

    @Test
    fun `optional profile setting does not control IDEA backend orchestration`() {
        val project = projectFixture.get()
        val defaults = KastConfig.defaults()
        val config = defaults.copy(
            projectOpen = defaults.projectOpen.copy(
                profileAutoInit = ProjectOpenProfileAutoInit(false),
                gradleLoadEnabled = ProjectOpenGradleLoadEnabled(false),
            ),
        )
        var backendStarted = false
        var indexStarted = false

        val requestedStart = KastProjectOpenAutoIndexing.execute(
            project = project,
            config = config,
            startBackend = { _, startupConfig ->
                assertSame(config, startupConfig)
                backendStarted = true
            },
            startReferenceIndex = { indexStarted = true },
            failReadiness = { _, _ -> },
        )

        assertTrue(requestedStart)
        assertTrue(backendStarted)
        assertTrue(indexStarted)
    }

    @Test
    fun `backend startup failure is contained before Gradle load and indexing`() {
        val project = projectFixture.get()
        var requestedGradleLoad = false
        var requestedIndex = false

        val requestedStart = KastProjectOpenAutoIndexing.execute(
            project = project,
            config = KastConfig.defaults(),
            loadGradleProject = { _, _, _ ->
                requestedGradleLoad = true
                error("Gradle load must not run after backend startup fails")
            },
            startBackend = { _, _ -> error("compatibility metadata failed") },
            startReferenceIndex = { requestedIndex = true },
            failReadiness = { _, _ -> requestedIndex = true },
        )

        assertFalse(requestedStart)
        assertFalse(requestedGradleLoad)
        assertFalse(requestedIndex)
    }

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

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            IdeaProjectIndexer(
                project = project,
                workspaceRoot = workspaceRoot,
                store = store,
                cancelled = { false },
                readGradleWorkspaceModel = { completeGradleModel },
            ).indexProject(KastConfig.defaults())

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
