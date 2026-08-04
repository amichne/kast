package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.contract.query.SemanticGraphQuery
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.semantic.semanticGraphContentHash
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class NativeSemanticGraphContextVersionTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val sourceFileFixture = sourceRootFixture.psiFileFixture("Canonical.kt", NativeSemanticGraphSources.canonical)

    @TempDir
    lateinit var storeRoot: Path

    @Test
    fun `semantic graph commits the exact workspace semantic context stage version`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = sourceFileFixture.get()
        waitUntilIndexesAreReady(project)
        val sourceFilePath = Path.of(sourceFile.virtualFile.path).toRealPath()
        val workspaceRoot = sourceFilePath.parent
        val desiredVersions = semanticContextStageVersions(WorkspaceStateIdentity("semantic-context-wsid"))

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(
                listOf(
                    fileInventoryEntry(
                        workspaceRoot,
                        sourceFilePath.toString(),
                        Files.getLastModifiedTime(sourceFilePath).toMillis(),
                        semanticGraphContentHash(workspaceSourcePath(workspaceRoot, sourceFilePath.toString())),
                        null,
                        null,
                    ),
                ),
                desiredVersions,
            )
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(500, 30_000, 4),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                backend.reconcileSemanticGraphForTest(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    ).parsed(),
                )
            }

            assertEquals(
                desiredVersions.semanticGraph,
                requireNotNull(store.fileStageOutcome(sourceFile.virtualFile.path, FileIndexStage.SEMANTIC_GRAPH)).version,
            )
        }
    }

    private fun sourceIndexStore(workspaceRoot: Path): SqliteSourceIndexStore = SqliteSourceIndexStore(
        WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).copy(
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(storeRoot.resolve("source-index.db")),
        ),
    )
}
