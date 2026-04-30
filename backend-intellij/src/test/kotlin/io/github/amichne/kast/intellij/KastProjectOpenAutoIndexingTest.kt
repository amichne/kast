package io.github.amichne.kast.intellij

import com.intellij.openapi.project.Project
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.RemoteIndexConfig
import io.github.amichne.kast.indexstore.FileIndexUpdate
import io.github.amichne.kast.indexstore.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.sourceIndexDatabasePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
    fun `project open starts backend and reference indexing when intellij backend is enabled`() {
        val project = projectFixture.get()
        var loadedWorkspaceRoot: Path? = null
        var startedProject: Project? = null

        val started = KastProjectOpenAutoIndexing.execute(
            project = project,
            loadConfig = { workspaceRoot ->
                loadedWorkspaceRoot = workspaceRoot
                KastConfig.defaults()
            },
            startBackendAndIndexReferences = { startedProject = it },
        )

        assertTrue(started)
        assertSame(project, startedProject)
        assertNotNull(loadedWorkspaceRoot)
        assertEquals(loadedWorkspaceRoot, loadedWorkspaceRoot?.toAbsolutePath()?.normalize())
    }

    @Test
    fun `project open skips backend and reference indexing when intellij backend is disabled`() {
        val project = projectFixture.get()
        var started = false
        val disabledConfig = KastConfig.defaults().let { config ->
            config.copy(
                backends = config.backends.copy(
                    intellij = config.backends.intellij.copy(enabled = false),
                ),
            )
        }

        val requestedStart = KastProjectOpenAutoIndexing.execute(
            project = project,
            loadConfig = { disabledConfig },
            startBackendAndIndexReferences = { started = true },
        )

        assertFalse(requestedStart)
        assertFalse(started)
    }

    @Test
    fun `project indexer prepopulates SQLite source identifiers and references from IntelliJ PSI files`() {
        val project = projectFixture.get()
        val targetFile = targetFileFixture.get()
        val callerFile = callerFileFixture.get()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(callerFile.virtualFile.path).parent.toAbsolutePath().normalize()
        val callerPath = Path.of(callerFile.virtualFile.path).toAbsolutePath().normalize().toString()
        val targetPath = Path.of(targetFile.virtualFile.path).toAbsolutePath().normalize().toString()

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            IntelliJProjectIndexer(
                project = project,
                workspaceRoot = workspaceRoot,
                store = store,
                cancelled = { false },
            ).indexProject(KastConfig.defaults())

            val snapshot = store.loadSourceIndexSnapshot()
            assertEquals(listOf(callerPath), snapshot.candidatePathsByIdentifier.getValue("caller"))
            assertTrue(snapshot.candidatePathsByIdentifier.getValue("target").contains(targetPath))
            assertEquals("demo", snapshot.packageByPath.getValue(callerPath))
            assertEquals(listOf("demo.target"), snapshot.importsByPath.getValue(callerPath))
            assertTrue(store.loadManifest().orEmpty().keys.containsAll(setOf(callerPath, targetPath)))
            assertTrue(store.referencesToSymbol("demo.target").any { row -> row.sourcePath == callerPath })
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
                enabled = true,
                sourceIndexUrl = remoteDbPath.toUri().toString(),
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
