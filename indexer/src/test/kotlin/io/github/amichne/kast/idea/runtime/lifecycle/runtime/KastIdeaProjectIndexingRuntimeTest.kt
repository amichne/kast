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
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@TestApplication
class KastIdeaProjectIndexingRuntimeTest {
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
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(),
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            semanticGraphIndexer = { _, _, _ -> error("graph unavailable") },
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
            refreshWorkspace = { _, _, _ -> },
            resolveWorkspaceStateIdentity = { io.github.amichne.kast.idea.transition.WorkspaceStateIdentity("test") },
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
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(),
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            semanticGraphIndexer = { scope, _, _ -> observed.set(scope) },
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
            refreshWorkspace = { _, _, _ -> },
            resolveWorkspaceStateIdentity = { io.github.amichne.kast.idea.transition.WorkspaceStateIdentity("test") },
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
    fun `workspace generation publication completes before admission becomes ready`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = tempDir.resolve("atomic-publication")
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val admission = readyAdmission(project)
        val stateDuringPublication = AtomicReference<IdeaIndexSemanticAdmission.Status>()
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(
                onCommit = { stateDuringPublication.set(admission.status()) },
            ),
            indexStore = store,
            semanticAdmission = admission,
            runProjectIndexing = { _, _ -> },
            waitForNextPass = { false },
            refreshWorkspace = { _, _, _ -> },
            resolveWorkspaceStateIdentity = {
                io.github.amichne.kast.idea.transition.WorkspaceStateIdentity("verified-workspace")
            },
        )

        try {
            indexing.start()
            indexing.awaitTermination()

            assertTrue(stateDuringPublication.get() is IdeaIndexSemanticAdmission.Status.Pending)
            val ready = admission.status() as IdeaIndexSemanticAdmission.Status.Ready
            assertEquals("verified-workspace", ready.generation.identity.value)
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    @Test
    fun `workspace generation publication failure keeps admission blocked`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = tempDir.resolve("failed-publication")
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val admission = readyAdmission(project)
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(
                onCommit = { error("generation fsync failed") },
            ),
            indexStore = store,
            semanticAdmission = admission,
            runProjectIndexing = { _, _ -> },
            waitForNextPass = { false },
            refreshWorkspace = { _, _, _ -> },
            resolveWorkspaceStateIdentity = {
                io.github.amichne.kast.idea.transition.WorkspaceStateIdentity("verified-workspace")
            },
        )

        try {
            indexing.start()
            indexing.awaitTermination()

            val status = admission.status()
            assertTrue(status is IdeaIndexSemanticAdmission.Status.Failed)
            assertTrue((status as IdeaIndexSemanticAdmission.Status.Failed).detail.contains("fsync"))
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
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(),
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
            refreshWorkspace = { _, _, _ -> },
            resolveWorkspaceStateIdentity = { io.github.amichne.kast.idea.transition.WorkspaceStateIdentity("test") },
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
    fun `indexing retry is bounded before recovery audit`() {
        var failures = ConsecutiveIndexingFailures.none()
        listOf(250L, 500L, 1_000L, 2_000L, 5_000L).forEach { expectedMillis ->
            failures = failures.afterFailure()
            assertEquals(Duration.ofMillis(expectedMillis), failures.retryDelay)
        }
        repeat(95) { failures = failures.afterFailure() }
        assertEquals(Duration.ofSeconds(5), failures.retryDelay)
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
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(),
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

}
