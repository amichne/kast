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
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

@TestApplication
class NativeSemanticGraphAdmissionTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val canonicalFileFixture = sourceRootFixture.psiFileFixture("Canonical.kt", NativeSemanticGraphSources.canonical)

    @TempDir
    lateinit var storeRoot: Path

    @Test
    fun `public semantic graph stays read only while pending and coordinates missing facts when ready`() {
        val project = projectFixture.get()
        val sourceFile = canonicalFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val query = SemanticGraphQuery(
            filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
        ).parsed()
        var admission: IdeaIndexSemanticAdmission.Status =
            IdeaIndexSemanticAdmission.Status.Pending("Kotlin PSI is unavailable")
        val transitionSignals = mutableListOf<WorkspaceSignal>()

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            val backendAuthority = CompletableFuture<KastIndexerBackend>()
            val backend = KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority { admission },
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(
                    onReconcile = { request ->
                        transitionSignals += request.signal
                        backendAuthority.join().reconcileSemanticGraphForTest(query)
                        testPublishedWorkspaceGeneration()
                    },
                ),
            )
            check(backendAuthority.complete(backend))
            backend.use { backend ->
                repeat(3) {
                    assertThrows(ConflictException::class.java) {
                        runBlocking { backend.semanticGraph(query) }
                    }
                }
                assertEquals(
                    null,
                    store.fileStageOutcome(sourceFile.virtualFile.path, FileIndexStage.SEMANTIC_GRAPH),
                )
                assertTrue(
                    store.readSemanticGraph(listOf(SemanticGraphSourcePath.parse(sourceFile.name))).symbols.isEmpty(),
                )

                admission = IdeaIndexSemanticAdmission.Status.Ready(testPublishedWorkspaceGeneration())
                val result = runBlocking { backend.semanticGraph(query) }

                assertEquals(listOf(WorkspaceSignal.RecoveryAudit), transitionSignals)
                assertTrue(result.symbolCount.value > 0)
                assertEquals(
                    FileStageOutcomeStatus.COMPLETE,
                    store.fileStageOutcome(sourceFile.virtualFile.path, FileIndexStage.SEMANTIC_GRAPH)?.status,
                )
            }
        }
    }

    @Test
    fun `persisted graph refresh rejects kastignore paths`() {
        val project = projectFixture.get()
        val sourceFile = canonicalFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val ignoreFile = workspaceRoot.resolve(".kastignore")
        Files.writeString(ignoreFile, "Canonical.kt")

        try {
            sourceIndexStore(workspaceRoot).use { store ->
                store.ensureSchema()
                KastIndexerBackend(
                    project = project,
                    workspaceRoot = workspaceRoot,
                    limits = limits(),
                    semanticGraphStore = store,
                    workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                    workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
                ).use { backend ->
                    val error = assertThrows(ValidationException::class.java) {
                        runBlocking {
                            backend.semanticGraph(
                                SemanticGraphQuery(
                                    filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                                ).parsed(),
                            )
                        }
                    }
                    assertTrue(error.message.contains("persisted-index scope"))
                }
            }
        } finally {
            Files.deleteIfExists(ignoreFile)
        }
    }

    private fun limits(): ServerLimits =
        ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4)

    private fun sourceIndexStore(workspaceRoot: Path): SqliteSourceIndexStore =
        SqliteSourceIndexStore(
            WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).copy(
                sourceIndexDatabasePath = NormalizedPath.ofAbsolute(storeRoot.resolve("source-index.db")),
            ),
        )
}
