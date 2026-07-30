package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.protocol.WorkspaceProjectModelIncompleteException
import io.github.amichne.kast.api.protocol.WorkspaceProjectModelIncompleteReason
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@TestApplication
class IdeaProjectIndexerCancellationTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()

        private const val firstSource = """
            package current

            class First
        """

        private const val secondSource = """
            package current

            class Second
        """
    }

    @TempDir
    lateinit var tempDir: Path

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val firstFileFixture = sourceRootFixture.psiFileFixture("First.kt", firstSource)
    private val secondFileFixture = sourceRootFixture.psiFileFixture("Second.kt", secondSource)

    @Test
    fun `cancellation before scanning preserves committed source index and generation`() {
        assertCancellationPreservesCommittedIndex(
            databaseName = "cancelled-before.db",
            cancelled = { true },
        )
    }

    @Test
    fun `cancellation during scanning preserves committed source index and generation`() {
        var cancellationChecks = 0

        assertCancellationPreservesCommittedIndex(
            databaseName = "cancelled-during.db",
            cancelled = { cancellationChecks++ > 0 },
        )
    }

    @Test
    fun `focused relationship refresh keeps cancellation terminal`() {
        val project = projectFixture.get()
        val firstFile = firstFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(firstFile.virtualFile.path).parent.toAbsolutePath().normalize()

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            assertThrows(ProcessCanceledException::class.java) {
                IdeaProjectIndexer(
                    project = project,
                    workspaceRoot = workspaceRoot,
                    store = store,
                    cancelled = { true },
                ).refreshSymbolRelationships(listOf(firstFile.virtualFile.path))
            }
        }
    }

    @Test
    fun `incomplete imported Gradle model preserves committed source index and generation`() {
        val project = projectFixture.get()
        val firstFile = firstFileFixture.get()
        secondFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(firstFile.virtualFile.path).parent.toAbsolutePath().normalize()
        val workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).copy(
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(tempDir.resolve("incomplete-model.db")),
        )
        val priorPath = workspaceRoot.resolve("prior/Committed.kt").toString()
        val incompleteGradleModel = IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
            emptyList(),
            false,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )

        SqliteSourceIndexStore(workspaceIdentity).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    FileIndexUpdate(
                        path = priorPath,
                        identifiers = setOf("PreviouslyCommitted"),
                        packageName = "prior",
                        modulePath = ":prior",
                        sourceSet = "main",
                        imports = setOf("prior.Dependency"),
                        wildcardImports = emptySet(),
                    ),
                ),
                manifest = mapOf(priorPath to 17L),
            )
            val priorSnapshot = store.loadSourceIndexSnapshot()
            val priorManifest = store.loadManifest()
            val priorGeneration = store.readGeneration()

            val failure = assertThrows(WorkspaceProjectModelIncompleteException::class.java) {
                IdeaProjectIndexer(
                    project = project,
                    workspaceRoot = workspaceRoot,
                    store = store,
                    cancelled = { false },
                    workspaceIdentity = workspaceIdentity,
                    readGradleWorkspaceModel = { incompleteGradleModel },
                ).indexSourceIdentifiers()
            }

            assertEquals(WorkspaceProjectModelIncompleteReason.PROJECT_MODEL_UNAVAILABLE, failure.reason)
            assertEquals(priorSnapshot, store.loadSourceIndexSnapshot())
            assertEquals(priorManifest, store.loadManifest())
            assertEquals(priorGeneration, store.readGeneration())
        }
    }

    private fun assertCancellationPreservesCommittedIndex(
        databaseName: String,
        cancelled: () -> Boolean,
    ) {
        val project = projectFixture.get()
        val firstFile = firstFileFixture.get()
        secondFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(firstFile.virtualFile.path).parent.toAbsolutePath().normalize()
        val workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).copy(
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(tempDir.resolve(databaseName)),
        )
        val priorPath = workspaceRoot.resolve("prior/Committed.kt").toString()
        val completeGradleModel = IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
            emptyList(),
            true,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )

        SqliteSourceIndexStore(workspaceIdentity).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    FileIndexUpdate(
                        path = priorPath,
                        identifiers = setOf("PreviouslyCommitted"),
                        packageName = "prior",
                        modulePath = ":prior",
                        sourceSet = "main",
                        imports = setOf("prior.Dependency"),
                        wildcardImports = emptySet(),
                    ),
                ),
                manifest = mapOf(priorPath to 17L),
            )
            val priorSnapshot = store.loadSourceIndexSnapshot()
            val priorManifest = store.loadManifest()
            val priorGeneration = store.readGeneration()

            IdeaProjectIndexer(
                project = project,
                workspaceRoot = workspaceRoot,
                store = store,
                cancelled = cancelled,
                workspaceIdentity = workspaceIdentity,
                readGradleWorkspaceModel = { completeGradleModel },
            ).indexSourceIdentifiers()

            assertEquals(priorSnapshot, store.loadSourceIndexSnapshot())
            assertEquals(priorManifest, store.loadManifest())
            assertEquals(priorGeneration, store.readGeneration())
        }
    }
}
