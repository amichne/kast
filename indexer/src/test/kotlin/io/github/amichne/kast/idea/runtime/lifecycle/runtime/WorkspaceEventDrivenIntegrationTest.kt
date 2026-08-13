package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionGuard
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionStatus
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.idea.transition.WorkspaceVfsEventObserver
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@TestApplication
class WorkspaceEventDrivenIntegrationTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture(openAfterCreation = true)
    }

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `external source edit withdraws ready through production VFS observation and publishes edited bytes`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = tempDir.resolve("external-edit").also(Files::createDirectories)
        val source = workspaceRoot.resolve("src/main/kotlin/demo/Target.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "package demo\nfun value() = 1\n")
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source))

        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val admission = IdeaIndexSemanticAdmission(
            project = project,
            inspectProject = { IdeaIndexSemanticAdmission.Inspection.Ready },
        )
        val delivered = LinkedBlockingQueue<WorkspaceSignal>()
        val publications = LinkedBlockingQueue<WorkspaceStateIdentity>()
        val passCount = AtomicInteger()
        val editedPassStarted = CountDownLatch(1)
        val releaseEditedPass = CountDownLatch(1)
        val publication = TestWorkspaceGenerationPublication(onCommit = publications::offer)
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            workspaceGenerationPublication = publication,
            indexStore = store,
            semanticAdmission = admission,
            observeWorkspaceEvents = { observedProject, scope, sink ->
                WorkspaceVfsEventObserver.subscribe(observedProject, scope) { signal ->
                    sink(signal)
                    delivered.offer(signal)
                }
            },
            refreshWorkspace = { _, _, _ -> },
            runProjectIndexing = { _, _ ->
                if (passCount.incrementAndGet() == 2) {
                    editedPassStarted.countDown()
                    assertTrue(releaseEditedPass.await(10, TimeUnit.SECONDS), "edited pass was not released")
                }
            },
            resolveWorkspaceStateIdentity = { sourceIdentity(source) },
        )

        try {
            indexing.start()
            val initial = checkNotNull(publications.poll(10, TimeUnit.SECONDS)) {
                "initial generation was not published"
            }
            assertEquals(sourceIdentity(source), initial)
            assertTrue(awaitReady(admission), "initial generation did not open READY")

            Files.writeString(source, "package demo\nfun value() = 2\n")
            syncRefresh()

            assertTrue(awaitSignal(delivered, WorkspaceSignal.Source), "production VFS listener missed the edit")
            assertTrue(editedPassStarted.await(10, TimeUnit.SECONDS), "edited reconciliation did not start")
            assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)
            assertTrue(publications.isEmpty(), "an edited generation became current before reconciliation completed")

            releaseEditedPass.countDown()
            val edited = checkNotNull(publications.poll(10, TimeUnit.SECONDS)) {
                "edited generation was not published"
            }
            assertEquals(sourceIdentity(source), edited)
            assertNotEquals(initial, edited)
            assertEquals(
                2L,
                (publication.current() as PublishedWorkspaceGenerationState.Published).publication.generation.value,
            )
            assertTrue(awaitReady(admission), "edited generation did not open READY")
        } finally {
            releaseEditedPass.countDown()
            indexing.cancel()
            indexing.awaitTermination()
            store.close()
        }
    }

    @Test
    fun `real thousand file checkout publishes exactly one coherent final generation`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = tempDir.resolve("checkout-workspace").also(Files::createDirectories)
        val filterStarted = tempDir.resolve("checkout-filter-started")
        val filterRelease = tempDir.resolve("checkout-filter-release")
        val filter = createBlockingCheckoutFilter(tempDir, filterStarted, filterRelease)
        prepareThousandFileRepository(workspaceRoot, filter, filterRelease)
        val changedPathCount = readGitOutput(
            workspaceRoot,
            "diff",
            "--name-only",
            "state-a..state-b",
        ).lineSequence().count(String::isNotBlank)
        assertTrue(
            changedPathCount >= EVENT_DRIVEN_FILE_COUNT,
            "checkout fixture must change at least $EVENT_DRIVEN_FILE_COUNT paths",
        )
        val initialTree = treeIdentity(workspaceRoot)
        val virtualRoot = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(workspaceRoot))
        virtualRoot.refresh(false, true)
        repeat(EVENT_DRIVEN_FILE_COUNT) { index ->
            checkNotNull(
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(
                    workspaceRoot.resolve("src/main/kotlin/demo/File${index.toString().padStart(4, '0')}.kt"),
                ),
            )
        }
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(workspaceRoot.resolve(".git/index")))

        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val admission = IdeaIndexSemanticAdmission(
            project = project,
            inspectProject = { IdeaIndexSemanticAdmission.Inspection.Ready },
        )
        val guard = GitWorktreeTransitionGuard.exactRoot(workspaceRoot)
        val delivered = LinkedBlockingQueue<WorkspaceSignal>()
        val publications = LinkedBlockingQueue<WorkspaceStateIdentity>()
        val committed = CopyOnWriteArrayList<WorkspaceStateIdentity>()
        val refreshPasses = CopyOnWriteArrayList<Set<WorkspaceSignal>>()
        val publication = TestWorkspaceGenerationPublication { identity ->
            check(guard.inspect() is GitWorktreeTransitionStatus.Stable) {
                "a workspace generation was committed during an active Git transition"
            }
            committed += identity
            publications.offer(identity)
        }
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            workspaceGenerationPublication = publication,
            indexStore = store,
            semanticAdmission = admission,
            observeWorkspaceEvents = { observedProject, scope, sink ->
                WorkspaceVfsEventObserver.subscribe(observedProject, scope) { signal ->
                    sink(signal)
                    delivered.offer(signal)
                }
            },
            refreshWorkspace = { _, _, signals -> refreshPasses += signals },
            runProjectIndexing = { _, _ -> },
            resolveWorkspaceStateIdentity = { treeIdentity(workspaceRoot) },
        )
        var checkout: Process? = null
        val refreshing = AtomicBoolean(false)
        var refreshThread: Thread? = null

        try {
            indexing.start()
            assertEquals(initialTree, publications.poll(15, TimeUnit.SECONDS), "baseline generation")
            delivered.clear()
            refreshPasses.clear()
            Files.deleteIfExists(filterStarted)
            Files.deleteIfExists(filterRelease)

            checkout = startGitProcess(workspaceRoot, "checkout", "state-b")
            assertTrue(
                awaitPath(filterStarted, Duration.ofSeconds(10)),
                "real checkout did not enter its smudge filter"
            )
            val indexLock = Path.of(
                readGitOutput(workspaceRoot, "rev-parse", "--path-format=absolute", "--git-path", "index.lock"),
            )
            assertTrue(Files.isRegularFile(indexLock), "real checkout must hold the exact-worktree index lock")
            syncRefresh()

            assertTrue(
                awaitSignal(delivered, WorkspaceSignal.Source),
                "listener missed the first source change from the live checkout",
            )
            assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)
            Thread.sleep(500)
            assertTrue(publications.isEmpty(), "an intermediate checkout tree became current")
            assertTrue(guard.inspect() is GitWorktreeTransitionStatus.InProgress)

            refreshing.set(true)
            val activeRefreshThread = thread(isDaemon = true, name = "kast-checkout-vfs-refresh") {
                while (refreshing.get()) {
                    syncRefresh()
                    Thread.sleep(10)
                }
            }
            refreshThread = activeRefreshThread
            Files.createFile(filterRelease)
            val checkoutOutput = checkout.inputStream.use { it.readAllBytes().toString(Charsets.UTF_8) }
            assertEquals(0, checkout.waitFor(), "git checkout state-b failed: $checkoutOutput")
            refreshing.set(false)
            activeRefreshThread.join(10_000)
            assertFalse(activeRefreshThread.isAlive, "VFS refresh loop did not stop")
            syncRefresh()

            val finalTree = treeIdentity(workspaceRoot)
            assertNotEquals(initialTree, finalTree)
            val publishedFinal = checkNotNull(publications.poll(20, TimeUnit.SECONDS)) {
                "final checkout generation was not published"
            }
            assertEquals(finalTree, publishedFinal)
            Thread.sleep(500)
            assertTrue(publications.isEmpty(), "checkout published more than one final generation")
            assertEquals(listOf(initialTree, finalTree), committed)
            assertEquals(
                2L,
                (publication.current() as PublishedWorkspaceGenerationState.Published).publication.generation.value,
            )
            assertTrue(awaitReady(admission), "checkout generation did not open READY")
            assertEquals(1, refreshPasses.size, "checkout event storm must conflate into one final pass")
        } finally {
            Files.createDirectories(filterRelease.parent)
            if (!Files.exists(filterRelease)) Files.createFile(filterRelease)
            checkout?.let { process ->
                if (process.isAlive) process.destroyForcibly()
            }
            refreshing.set(false)
            refreshThread?.join(10_000)
            indexing.cancel()
            indexing.awaitTermination()
            store.close()
        }
    }
}
