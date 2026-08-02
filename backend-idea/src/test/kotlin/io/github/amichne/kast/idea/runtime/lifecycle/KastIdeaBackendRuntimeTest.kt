package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.GraphIndexingBatchSize
import io.github.amichne.kast.api.client.fields.PathsDescriptorDir
import io.github.amichne.kast.api.client.fields.PathsLogsDir
import io.github.amichne.kast.api.client.fields.PathsSocketDir
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.exists

@TestApplication
class KastIdeaBackendRuntimeTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()

        private const val targetSource = """
            package demo

            fun target(): String = "ok"
        """
    }

    @TempDir
    lateinit var tempDir: Path

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val targetFileFixture = sourceRootFixture.psiFileFixture("Target.kt", targetSource)

    @Test
    fun `runtime starts analysis server with configured backend name`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = targetFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).parent.toAbsolutePath().normalize()
        val socketPath = tempDir.resolve("kast-headless.sock")
        val descriptorDirectory = tempDir.resolve("descriptors")
        val config = KastConfig.defaults().let { defaults ->
            defaults.copy(
                indexing = defaults.indexing.copy(
                    graph = defaults.indexing.graph.copy(batchSize = GraphIndexingBatchSize(7)),
                ),
                paths = defaults.paths.copy(
                    descriptorDir = PathsDescriptorDir(descriptorDirectory.toString()),
                    logsDir = PathsLogsDir(tempDir.resolve("logs").toString()),
                    socketDir = PathsSocketDir(tempDir.toString()),
                ),
            )
        }

        KastIdeaBackendRuntime.start(
            project = project,
            workspaceRoot = workspaceRoot,
            socketPath = socketPath,
            config = config,
        ).use { runtime ->
            assertEquals("headless", runtime.backend.capabilities().backendName)
            assertEquals("headless", runtime.backend.runtimeStatus().backendName)
            val delegateField = runtime.backend.javaClass.getDeclaredField("delegate").apply { isAccessible = true }
            val pluginBackend = delegateField.get(runtime.backend) as KastPluginBackend
            assertEquals(GraphIndexingBatchSize(7), pluginBackend.semanticGraphBatchSize)
            pluginBackend.updateSemanticGraphBatchSize(GraphIndexingBatchSize(9))
            assertEquals(GraphIndexingBatchSize(9), pluginBackend.semanticGraphBatchSize)
            assertEquals(socketPath.toRealPath(), runtime.server.descriptor?.socketPath?.toPath())
            assertTrue(descriptorDirectory.resolve("daemons.json").exists())
        }
    }

    @Test
    fun `graph failure does not block the reference indexing pass`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = tempDir.resolve("independent-pipelines")
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val referencesRan = AtomicInteger()
        val retryDelays = mutableListOf<Long>()
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            semanticGraphIndexer = { _, _ -> error("graph unavailable") },
            runProjectIndexing = { _, graph ->
                graph(
                    IndexedSourceIdentifiers(
                        paths = workspaceSourcePaths(
                            workspaceRoot,
                            listOf(workspaceRoot.resolve("src/main/App.kt").toString()),
                        ),
                        criticalPaths = emptySet(),
                        unmatchedCriticalPatterns = emptyList(),
                    ),
                )
                referencesRan.incrementAndGet()
            },
            waitForNextPass = { delay -> retryDelays += delay; false },
        )

        try {
            indexing.start()
            indexing.awaitTermination()
            assertEquals(1, referencesRan.get())
            assertEquals(listOf(250L), retryDelays)
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    @Test
    fun `automatic graph indexing forwards files removed from persisted scope`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = tempDir.resolve("graph-tombstones")
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val removed = workspaceSourcePath(workspaceRoot, workspaceRoot.resolve("src/Removed.kt").toString()).rawPath
        val observed = AtomicReference<IndexedSourceIdentifiers>()
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            semanticGraphIndexer = { scope, _ -> observed.set(scope) },
            runProjectIndexing = { _, graph ->
                graph(
                    IndexedSourceIdentifiers(
                        paths = emptyList(),
                        criticalPaths = emptySet(),
                        unmatchedCriticalPatterns = emptyList(),
                        removedPaths = workspaceSourcePaths(workspaceRoot, listOf(removed)),
                    ),
                )
            },
            waitForNextPass = { false },
        )

        try {
            indexing.start()
            indexing.awaitTermination()
            assertEquals(listOf(removed), observed.get().removedPaths.map { it.rawPath })
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    @Test
    fun `invalid live scope falls back to the last valid config`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = tempDir.resolve("last-valid-scope")
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val initial = KastConfig.defaults()
        val invalid = initial.copy(
            indexing = initial.indexing.copy(
                graph = initial.indexing.graph.copy(batchSize = GraphIndexingBatchSize(7)),
            ),
        )
        val attemptedBatchSizes = mutableListOf<Int>()
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = initial,
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            liveConfigLoader = { _, _ -> invalid },
            runProjectIndexing = { live, _ ->
                attemptedBatchSizes += live.indexing.graph.batchSize.value
                if (live.indexing.graph.batchSize.value == 7) {
                    throw IndexingScopeConfigurationException.invalid("invalid live scope")
                }
            },
            waitForNextPass = { false },
        )

        try {
            indexing.start()
            indexing.awaitTermination()
            assertEquals(listOf(7, 32), attemptedBatchSizes)
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    @Test
    fun `indexing retry is bounded before periodic retry`() {
        assertEquals(250, indexingRetryDelayMillis(1))
        assertEquals(500, indexingRetryDelayMillis(2))
        assertEquals(1_000, indexingRetryDelayMillis(3))
        assertEquals(30_000, indexingRetryDelayMillis(4))
        assertEquals(30_000, indexingRetryDelayMillis(100))
    }

    @Test
    fun `indexing starts once and cancellation leaves its shared store open until the worker stops`() {
        val project = projectFixture.get()
        val sourceFile = targetFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).parent.toAbsolutePath().normalize()
        val config = KastConfig.defaults().let { defaults ->
            defaults.copy(
                paths = defaults.paths.copy(
                    descriptorDir = PathsDescriptorDir(tempDir.resolve("indexing-descriptors").toString()),
                    logsDir = PathsLogsDir(tempDir.resolve("indexing-logs").toString()),
                    socketDir = PathsSocketDir(tempDir.toString()),
                ),
            )
        }
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(
            project = project,
            workspaceRoot = workspaceRoot,
            descriptorDirectory = config.paths.descriptorDir.toPath(),
        )
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity)
        val workerCount = AtomicInteger()
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val admission = IdeaIndexSemanticAdmission(
            project = project,
            inspectProject = { IdeaIndexSemanticAdmission.Inspection.Pending("test hold") },
            pause = {
                workerCount.incrementAndGet()
                workerStarted.countDown()
                while (true) {
                    try {
                        if (releaseWorker.await(10, TimeUnit.MILLISECONDS)) break
                    } catch (_: InterruptedException) {
                        Thread.interrupted()
                    }
                }
            },
            maxWaitMillis = TimeUnit.MINUTES.toMillis(1),
            pollIntervalMillis = 1,
        )
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = config,
            indexStore = store,
            semanticAdmission = admission,
        )

        try {
            DumbModeTestUtils.computeInDumbModeSynchronously(project) {
                indexing.start()
                indexing.start()
            }
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(1, workerCount.get())

            ApplicationManager.getApplication().invokeAndWait { indexing.cancel() }
            val workerStopped = CountDownLatch(1)
            val waiter = thread(isDaemon = true) {
                indexing.awaitTermination()
                workerStopped.countDown()
            }
            assertFalse(workerStopped.await(100, TimeUnit.MILLISECONDS))
            store.ensureSchema()

            releaseWorker.countDown()
            assertTrue(workerStopped.await(5, TimeUnit.SECONDS))
            waiter.join(5_000)
            assertFalse(waiter.isAlive)
        } finally {
            releaseWorker.countDown()
            indexing.cancel()
            indexing.awaitTermination()
            store.close()
        }
    }

    private fun readyAdmission(project: Project): IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(
        project = project,
        inspectProject = { IdeaIndexSemanticAdmission.Inspection.Ready },
    )

    @Test
    fun `blocking runtime cleanup leaves the IDEA dispatch thread`() {
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val completion = AtomicReference<CompletableFuture<Unit>>()

        ApplicationManager.getApplication().invokeAndWait {
            completion.set(
                closeAfterLeavingIdeaDispatchThreadAsync(
                    threadName = "kast-idea-test-closer",
                ) {
                    closeStarted.countDown()
                    releaseClose.await()
                    closeCompleted.countDown()
                },
            )
        }

        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        assertFalse(closeCompleted.await(100, TimeUnit.MILLISECONDS))
        assertFalse(completion.get().isDone)

        releaseClose.countDown()
        assertTrue(closeCompleted.await(5, TimeUnit.SECONDS))
        completion.get().get(5, TimeUnit.SECONDS)
    }
}
