package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionGuard
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionStatus
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.idea.transition.WorkspaceVfsEventObserver
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
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
        private const val FILE_COUNT = 1_000
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
                WorkspaceSemanticGeneration(2),
                (publication.current() as PublishedWorkspaceGenerationState.Published).manifest.generation,
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
        val filter = createBlockingCheckoutFilter(filterStarted, filterRelease)
        prepareThousandFileRepository(workspaceRoot, filter, filterRelease)
        val changedPathCount = gitOutput(
            workspaceRoot,
            "diff",
            "--name-only",
            "state-a..state-b",
        ).lineSequence().count(String::isNotBlank)
        assertTrue(changedPathCount >= FILE_COUNT, "checkout fixture must change at least $FILE_COUNT paths")
        val initialTree = treeIdentity(workspaceRoot)
        val virtualRoot = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(workspaceRoot))
        virtualRoot.refresh(false, true)
        repeat(FILE_COUNT) { index ->
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

            checkout = gitProcess(workspaceRoot, "checkout", "state-b")
            assertTrue(awaitPath(filterStarted, Duration.ofSeconds(10)), "real checkout did not enter its smudge filter")
            val indexLock = Path.of(
                gitOutput(workspaceRoot, "rev-parse", "--path-format=absolute", "--git-path", "index.lock"),
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
                WorkspaceSemanticGeneration(2),
                (publication.current() as PublishedWorkspaceGenerationState.Published).manifest.generation,
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
    private fun prepareThousandFileRepository(
        repository: Path,
        filter: Path,
        filterRelease: Path,
    ) {
        git(repository, "init", "--initial-branch=state-a")
        git(repository, "config", "user.name", "Kast Test")
        git(repository, "config", "user.email", "kast@example.invalid")
        git(repository, "config", "filter.kast-proof.required", "true")
        git(repository, "config", "filter.kast-proof.clean", "cat")
        git(
            repository,
            "config",
            "filter.kast-proof.smudge",
            "${shellQuote(filter)} ${shellQuote(filter.resolveSibling("checkout-filter-started"))} " +
                shellQuote(filterRelease),
        )
        Files.writeString(repository.resolve(".gitattributes"), "zzzz/hold.proof filter=kast-proof\n")
        writeCheckoutTree(repository, "a")
        git(repository, "add", ".")
        git(repository, "commit", "-m", "state a")

        git(repository, "switch", "-c", "state-b")
        writeCheckoutTree(repository, "b")
        Files.deleteIfExists(repository.resolve("src/removed/Removed.kt"))
        Files.createDirectories(repository.resolve("src/added"))
        Files.writeString(repository.resolve("src/added/Added.kt"), "package added\nclass Added\n")
        git(repository, "add", "-A")
        git(repository, "commit", "-m", "state b")

        Files.createFile(filterRelease)
        git(repository, "switch", "state-a")
    }
    private fun writeCheckoutTree(repository: Path, state: String) {
        val sources = repository.resolve("src/main/kotlin/demo").also(Files::createDirectories)
        val semanticState = if (state == "a") "a" else "changed-state-b"
        repeat(FILE_COUNT) { index ->
            Files.writeString(
                sources.resolve("File${index.toString().padStart(4, '0')}.kt"),
                "package demo\nclass File$index { val state = \"$semanticState\" }\n",
            )
        }
        Files.createDirectories(repository.resolve("src/removed"))
        Files.writeString(repository.resolve("src/removed/Removed.kt"), "package removed\nclass Removed$state\n")
        Files.writeString(repository.resolve("build.gradle.kts"), "version = \"$state\"\n")
        Files.writeString(repository.resolve("settings.gradle.kts"), "rootProject.name = \"checkout-$state\"\n")
        Files.writeString(repository.resolve(".kastignore"), "ignored-$state/**\n")
        Files.createDirectories(repository.resolve(".idea"))
        Files.writeString(repository.resolve(".idea/compiler.xml"), "<compiler state=\"$state\"/>\n")
        Files.createDirectories(repository.resolve("zzzz"))
        Files.writeString(repository.resolve("zzzz/hold.proof"), "hold-$state\n")
    }
    private fun createBlockingCheckoutFilter(started: Path, release: Path): Path {
        val filter = tempDir.resolve("checkout-filter.sh")
        Files.writeString(
            filter,
            """#!/bin/sh
started="${'$'}1"
release="${'$'}2"
: > "${'$'}started"
while [ ! -e "${'$'}release" ]; do sleep 0.01; done
cat
""",
        )
        assertTrue(filter.toFile().setExecutable(true), "checkout filter must be executable")
        Files.deleteIfExists(started)
        Files.deleteIfExists(release)
        return filter
    }
    private fun sourceIdentity(source: Path): WorkspaceStateIdentity =
        WorkspaceStateIdentity(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))))

    private fun treeIdentity(root: Path): WorkspaceStateIdentity {
        val digest = MessageDigest.getInstance("SHA-256")
        val gitDirectory = root.resolve(".git")
        Files.walk(root).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) && !path.startsWith(gitDirectory) }
                .sorted()
                .forEach { path ->
                    digest.update(root.relativize(path).toString().toByteArray())
                    digest.update(0.toByte())
                    digest.update(Files.readAllBytes(path))
                    digest.update(0.toByte())
                }
        }
        return WorkspaceStateIdentity(HexFormat.of().formatHex(digest.digest()))
    }
    private fun awaitSignal(
        delivered: LinkedBlockingQueue<WorkspaceSignal>,
        expected: WorkspaceSignal,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (delivered.poll(100, TimeUnit.MILLISECONDS) == expected) return true
        }
        return false
    }

    private fun awaitPath(path: Path, timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun awaitReady(admission: IdeaIndexSemanticAdmission): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (admission.status() is IdeaIndexSemanticAdmission.Status.Ready) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun syncRefresh() {
        ApplicationManager.getApplication().invokeAndWait {
            VirtualFileManager.getInstance().syncRefresh()
        }
    }

    private fun git(directory: Path, vararg arguments: String) {
        runGitCommand(directory, *arguments)
    }

    private fun gitOutput(directory: Path, vararg arguments: String): String =
        readGitOutput(directory, *arguments)

    private fun gitProcess(directory: Path, vararg arguments: String): Process =
        startGitProcess(directory, *arguments)

    private fun shellQuote(path: Path): String = "'${path.toAbsolutePath().toString().replace("'", "'\\''")}'"
}
