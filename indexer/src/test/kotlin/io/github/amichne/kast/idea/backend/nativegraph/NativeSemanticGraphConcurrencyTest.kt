package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.client.fields.GraphIndexingBatchSize
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.contract.query.SemanticGraphQuery
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@TestApplication
class NativeSemanticGraphConcurrencyTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()

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

        private const val boundaryTarget = """
            package demo

            class BoundaryTarget
        """
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val canonicalFileFixture = sourceRootFixture.psiFileFixture("Canonical.kt", canonicalSource)
    private val boundaryTargetFixture = sourceRootFixture.psiFileFixture("BoundaryTarget.kt", boundaryTarget)

    @TempDir
    lateinit var storeRoot: Path

    @Test
    fun `multi file refresh releases IDEA read access between files`() = runBlocking {
        val project = projectFixture.get()
        val files = listOf(canonicalFileFixture.get(), boundaryTargetFixture.get())
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(files.first().virtualFile.path).toRealPath().parent
        val firstReadEntered = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val writeStarted = CountDownLatch(1)
        val writeCompleted = CountDownLatch(1)
        val readCount = AtomicInteger()
        val psiGeneration = AtomicLong(1)
        val secondReadObservedCompletedWrite = AtomicBoolean(false)
        val observer = IdeaReadEpochObserver { kind ->
            if (kind != IdeaReadEpochKind.SEMANTIC_GRAPH) return@IdeaReadEpochObserver
            when (readCount.incrementAndGet()) {
                1 -> {
                    firstReadEntered.countDown()
                    assertTrue(releaseFirstRead.await(10, TimeUnit.SECONDS))
                }
                2 -> secondReadObservedCompletedWrite.set(writeCompleted.count == 0L)
            }
        }

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = psiGeneration::get,
                readEpochObserver = observer,
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                supervisorScope {
                    val refresh = async(Dispatchers.Default) {
                        backend.reconcileSemanticGraphForTest(
                            SemanticGraphQuery(
                                filePaths = files.map { file -> SemanticGraphPath.parse(file.virtualFile.path) },
                            ).parsed(),
                        )
                    }
                    assertTrue(firstReadEntered.await(10, TimeUnit.SECONDS))

                    val application = ApplicationManager.getApplication()
                    application.invokeLater {
                        writeStarted.countDown()
                        application.runWriteAction {
                            psiGeneration.incrementAndGet()
                            writeCompleted.countDown()
                        }
                    }
                    assertTrue(writeStarted.await(10, TimeUnit.SECONDS))
                    assertFalse(writeCompleted.await(100, TimeUnit.MILLISECONDS))

                    releaseFirstRead.countDown()
                    val failure = runCatching { refresh.await() }.exceptionOrNull()
                    assertTrue(writeCompleted.await(10, TimeUnit.SECONDS))
                    assertTrue(failure is ConflictException, "expected PSI generation conflict, got $failure")
                    assertTrue(store.semanticGraphSourcePaths().isEmpty())
                }
            }
        }

        assertEquals(files.size, readCount.get())
        assertTrue(
            secondReadObservedCompletedWrite.get(),
            "A pending IDEA write action must complete before the next semantic graph file read",
        )
    }

    @Test
    fun `cancelling batch two preserves the committed first batch`() = runBlocking {
        val project = projectFixture.get()
        val files = listOf(canonicalFileFixture.get(), boundaryTargetFixture.get())
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(files.first().virtualFile.path).toRealPath().parent
        val secondReadEntered = CountDownLatch(1)
        val releaseSecondRead = CountDownLatch(1)
        val readCount = AtomicInteger()
        val observer = IdeaReadEpochObserver { kind ->
            if (kind == IdeaReadEpochKind.SEMANTIC_GRAPH && readCount.incrementAndGet() == 2) {
                secondReadEntered.countDown()
                assertTrue(releaseSecondRead.await(10, TimeUnit.SECONDS))
            }
        }

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                readEpochObserver = observer,
                semanticGraphBatchSize = GraphIndexingBatchSize(1),
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                val refresh = async(Dispatchers.Default) {
                    backend.reconcileSemanticGraphForTest(
                        SemanticGraphQuery(
                            filePaths = files.map { file -> SemanticGraphPath.parse(file.virtualFile.path) },
                        ).parsed(),
                    )
                }
                assertTrue(secondReadEntered.await(10, TimeUnit.SECONDS))

                refresh.cancel()
                releaseSecondRead.countDown()
                val failure = runCatching { refresh.await() }.exceptionOrNull()

                assertTrue(failure is CancellationException, "expected cancellation, got $failure")
                assertEquals(2, readCount.get())
                assertEquals(
                    setOf(
                        io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath.parse(
                            files.minBy { file -> file.virtualFile.path }.name,
                        ),
                    ),
                    store.semanticGraphSourcePaths(),
                )
            }
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
