package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@TestApplication
class KastFocusedRelationshipRefreshTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture(openAfterCreation = true)
    }

    @TempDir
    lateinit var tempDir: Path

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val callerFileFixture = sourceRootFixture.psiFileFixture(
        "Caller.kt",
        """
        package demo

        fun caller(): String = target()
        """.trimIndent(),
    )
    private val targetFileFixture = sourceRootFixture.psiFileFixture(
        "Target.kt",
        """
        package demo

        fun target(): String = "ok"
        """.trimIndent(),
    )

    @Test
    fun `unchanged focused refresh does not recapture the complete workspace inventory`() {
        val project = projectFixture.get()
        val callerFile = callerFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(callerFile.virtualFile.path).parent.toAbsolutePath().normalize()
        val workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).copy(
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(tempDir.resolve("focused-current.db")),
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
            IdeaProjectIndexer(
                project = project,
                workspaceRoot = workspaceRoot,
                store = store,
                cancelled = { false },
                workspaceIdentity = workspaceIdentity,
                readGradleWorkspaceModel = { completeGradleModel },
            ).indexProject(KastConfig.defaults())

            val relationshipScans = mutableListOf<String>()
            IdeaProjectIndexer(
                project = project,
                workspaceRoot = workspaceRoot,
                store = store,
                cancelled = { false },
                workspaceIdentity = workspaceIdentity,
                readGradleWorkspaceModel = { error("focused refresh recaptured complete inventory") },
                onRelationshipFileScan = relationshipScans::add,
            ).refreshSymbolRelationships(listOf(callerFile.virtualFile.path))

            assertTrue(relationshipScans.isEmpty(), "unchanged relationships must remain cached")
        }
    }

    @Test
    fun `deleted focused refresh reconciles only removed inventory`() {
        val project = projectFixture.get()
        val callerFile = callerFileFixture.get()
        val targetFile = targetFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(callerFile.virtualFile.path).parent.toAbsolutePath().normalize()
        val workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).copy(
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(tempDir.resolve("focused-deleted.db")),
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
            IdeaProjectIndexer(
                project = project,
                workspaceRoot = workspaceRoot,
                store = store,
                cancelled = { false },
                workspaceIdentity = workspaceIdentity,
                readGradleWorkspaceModel = { completeGradleModel },
            ).indexProject(KastConfig.defaults())

            val callerPath = callerFile.virtualFile.path
            val targetPath = targetFile.virtualFile.path
            IdeaProjectIndexer(
                project = project,
                workspaceRoot = workspaceRoot,
                store = store,
                cancelled = { false },
                workspaceIdentity = workspaceIdentity,
                readGradleWorkspaceModel = { error("deleted refresh recaptured complete inventory") },
            ).refreshSymbolRelationships(
                filePaths = emptyList(),
                removedFilePaths = listOf(targetPath),
            )

            assertFalse(store.loadManifest().orEmpty().containsKey(targetPath))
            assertFalse(
                store.loadSourceIndexSnapshot().candidatePathsByIdentifier["target"].orEmpty().contains(targetPath),
            )
            assertNull(store.fileStageOutcome(targetPath, FileIndexStage.RELATIONSHIPS))
            assertTrue(
                store.referencesFromFile(callerPath).any { reference ->
                    reference.targetFqName == "demo.target" && reference.targetPath == null
                },
            )
            assertTrue(
                store.pendingFileStages(FileIndexStage.RELATIONSHIPS).any { work -> work.path == callerPath },
            )
        }
    }
}
