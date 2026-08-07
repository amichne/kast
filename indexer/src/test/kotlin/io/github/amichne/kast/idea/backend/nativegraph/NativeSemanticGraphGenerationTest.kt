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
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.contract.query.SemanticGraphQuery
import io.github.amichne.kast.api.contract.result.SemanticGraphFileStatus
import io.github.amichne.kast.api.contract.result.SemanticGraphGeneration
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@TestApplication
class NativeSemanticGraphGenerationTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture(openAfterCreation = true)

        private const val canonicalSource = """
            package demo

            annotation class Marker

            sealed class Parent<T> {
                open fun inherited(value: T): T = value
            }

            class Box<T> @Marker constructor(val value: T) : Parent<T>() where T : Any {
                @Marker
                var label: String = "label"

                override fun inherited(value: T): T = value
                fun pick(value: String): String = value
                fun pick(value: Int): Int = value
            }

            class Constructed {
                constructor(value: String)
                constructor(value: Int)
            }

            fun construct(): Constructed = Constructed(1)
        """

        private const val boundarySource = """
            package demo

            fun reachBoundary(): BoundaryTarget = BoundaryTarget()
        """

        private const val boundaryTarget = """
            package demo

            class BoundaryTarget
        """
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val canonicalFileFixture = sourceRootFixture.psiFileFixture("Canonical.kt", canonicalSource)
    private val boundarySourceFixture = sourceRootFixture.psiFileFixture("BoundarySource.kt", boundarySource)
    private val boundaryTargetFixture = sourceRootFixture.psiFileFixture("BoundaryTarget.kt", boundaryTarget)

    @TempDir
    lateinit var storeRoot: Path

    @Test
    fun `source scope excludes unselected targets and widens additively`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = boundarySourceFixture.get()
        val targetFile = boundaryTargetFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val sourcePath = SemanticGraphPath.parse(sourceFile.virtualFile.path)
        val targetPath = SemanticGraphPath.parse(targetFile.virtualFile.path)
        val indexingProgress = WorkspaceIndexingProgressAuthority()
        fun query(path: SemanticGraphPath) = SemanticGraphQuery(filePaths = listOf(path)).parsed()
        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceIndexingProgress = indexingProgress,
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                val result = backend.reconcileSemanticGraphForTest(query(sourcePath))
                assertTrue(result.coverage.omittedExternalTargetCount.value > 0)
                val observedProgress = indexingProgress.observe()
                assertTrue(observedProgress is WorkspaceIndexingProgressObservation.Observed)
                assertEquals(
                    FileIndexStage.SEMANTIC_GRAPH,
                    (observedProgress as WorkspaceIndexingProgressObservation.Observed).activity.stage,
                )
                val excluded = store.readSemanticGraph(listOf(SemanticGraphSourcePath.parse("BoundarySource.kt")))
                assertTrue(excluded.boundarySymbols.none { symbol -> symbol.name.value == "BoundaryTarget" })
                assertTrue(excluded.relations.none { relation -> relation.targetKey.value.contains("BoundaryTarget") })
                backend.reconcileSemanticGraphForTest(query(targetPath))
                backend.reconcileSemanticGraphForTest(query(sourcePath))
            }
            val snapshot = store.readSemanticGraph(listOf(SemanticGraphSourcePath.parse("BoundarySource.kt")))
            val boundary = snapshot.boundarySymbols.single { symbol -> symbol.name.value == "BoundaryTarget" }
            assertTrue(snapshot.relations.any { relation -> relation.targetKey == boundary.canonicalKey })
        }
    }

    @Test
    fun `target content changes invalidate cached semantic callers`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = boundarySourceFixture.get()
        val targetFile = boundaryTargetFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val query = SemanticGraphQuery(
            filePaths = listOf(sourceFile, targetFile)
                .map { file -> SemanticGraphPath.parse(file.virtualFile.path) },
        ).parsed()
        val document = runIdeaReadAction {
            FileDocumentManager.getInstance().getDocument(targetFile.virtualFile)!!
        }
        val originalText = runIdeaReadAction { document.text }

        try {
            sourceIndexStore(workspaceRoot).use { store ->
                store.ensureSchema()
                KastIndexerBackend(
                    project = project,
                    workspaceRoot = workspaceRoot,
                    limits = limits(),
                    semanticGraphStore = store,
                    psiGeneration = { 1L },
                    workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                    workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
                ).use { backend ->
                    backend.reconcileSemanticGraphForTest(query)
                    replaceDocument(project, document, "$originalText\nval targetRevision = 2\n")

                    val refreshed = backend.reconcileSemanticGraphForTest(query)

                    assertEquals(
                        setOf(
                            SemanticGraphSourcePath.parse(sourceFile.name),
                            SemanticGraphSourcePath.parse(targetFile.name),
                        ),
                        refreshed.coverage.files
                            .filter { file -> file.status == SemanticGraphFileStatus.REFRESHED }
                            .mapTo(mutableSetOf()) { file -> file.path },
                    )
                }
            }
        } finally {
            replaceDocument(project, document, originalText)
        }
    }

    @Test
    fun `concurrent removal prevents an older refresh from resurrecting cached nodes`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = canonicalFileFixture.get()
        val targetFile = boundaryTargetFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val sourcePath = SemanticGraphPath.parse(sourceFile.virtualFile.path)
        val targetPath = SemanticGraphPath.parse(targetFile.virtualFile.path)

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                val seeded = backend.reconcileSemanticGraphForTest(SemanticGraphQuery(filePaths = listOf(sourcePath)).parsed())
                assertTrue(seeded.symbolCount.value > 0)
            }
            val seededGeneration = store.readGeneration()

            val refreshReadEntered = CountDownLatch(1)
            val releaseRefreshRead = CountDownLatch(1)
            val observer = IdeaReadEpochObserver { kind ->
                if (kind == IdeaReadEpochKind.SEMANTIC_GRAPH) {
                    refreshReadEntered.countDown()
                    assertTrue(releaseRefreshRead.await(10, TimeUnit.SECONDS))
                }
            }
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                readEpochObserver = observer,
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                supervisorScope {
                    val refresh = async(Dispatchers.Default) {
                        backend.reconcileSemanticGraphForTest(
                            SemanticGraphQuery(
                                filePaths = listOf(sourcePath, targetPath),
                                expectedGeneration = SemanticGraphGeneration(seededGeneration.value),
                            ).parsed(),
                        )
                    }
                    assertTrue(refreshReadEntered.await(10, TimeUnit.SECONDS))
                    val removal = async(Dispatchers.Default) {
                        backend.reconcileSemanticGraphForTest(
                            SemanticGraphQuery(
                                filePaths = emptyList(),
                                removedFilePaths = listOf(sourcePath),
                                expectedGeneration = SemanticGraphGeneration(seededGeneration.value),
                            ).parsed(),
                        )
                    }

                    withTimeout(10_000) { removal.await() }
                    releaseRefreshRead.countDown()
                    val refreshFailure = runCatching { refresh.await() }.exceptionOrNull()

                    assertTrue(refreshFailure is ConflictException, "expected generation conflict, got $refreshFailure")
                    assertTrue(refreshFailure?.message.orEmpty().contains("retry"))
                    assertTrue(store.semanticGraphSourcePaths().isEmpty())
                }
            }
        }
    }

    @Test
    fun `removal only refresh rejects a changed PSI generation without deleting cached nodes`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = canonicalFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val sourcePath = SemanticGraphPath.parse(sourceFile.virtualFile.path)

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                backend.reconcileSemanticGraphForTest(SemanticGraphQuery(filePaths = listOf(sourcePath)).parsed())
            }
            val generation = store.readGeneration()
            val psiGeneration = AtomicLong()

            val failure = KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = psiGeneration::incrementAndGet,
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                runCatching {
                    backend.reconcileSemanticGraphForTest(
                        SemanticGraphQuery(
                            filePaths = emptyList(),
                            removedFilePaths = listOf(sourcePath),
                            expectedGeneration = SemanticGraphGeneration(generation.value),
                        ).parsed(),
                    )
                }.exceptionOrNull()
            }

            assertTrue(failure is ConflictException, "expected PSI generation conflict, got $failure")
            assertEquals(generation, store.readGeneration())
            assertEquals(setOf(SemanticGraphSourcePath.parse(sourceFile.name)), store.semanticGraphSourcePaths())
        }
    }

    @Test
    fun `stale expected generation rejects refresh before IDEA extraction`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = canonicalFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val sourcePath = SemanticGraphPath.parse(sourceFile.virtualFile.path)

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            val seededGeneration = KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                backend.reconcileSemanticGraphForTest(SemanticGraphQuery(filePaths = listOf(sourcePath)).parsed()).generation
            }
            store.replaceSemanticGraphFiles(
                updates = emptyList(),
                removedPaths = listOf(SemanticGraphSourcePath.parse(sourceFile.name)),
            )
            val readCount = AtomicInteger()
            val observer = IdeaReadEpochObserver { kind ->
                if (kind == IdeaReadEpochKind.SEMANTIC_GRAPH) readCount.incrementAndGet()
            }

            val failure = KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                readEpochObserver = observer,
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                runCatching {
                    backend.reconcileSemanticGraphForTest(
                        SemanticGraphQuery(
                            filePaths = listOf(sourcePath),
                            expectedGeneration = seededGeneration,
                        ).parsed(),
                    )
                }.exceptionOrNull()
            }

            assertTrue(failure is ConflictException, "expected generation conflict, got $failure")
            assertEquals(0, readCount.get())
            assertTrue(store.semanticGraphSourcePaths().isEmpty())
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

    private fun replaceDocument(
        project: Project,
        document: com.intellij.openapi.editor.Document,
        content: String,
    ) {
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction { document.setText(content) }
            PsiDocumentManager.getInstance(project).commitDocument(document)
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }
}
