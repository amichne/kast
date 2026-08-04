package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@TestApplication
class WorkspaceTransitionRuntimeTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
    }

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `buffered source event cannot bypass initial build semantic refresh`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, tempDir.resolve("buffered-source"))
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val refreshedSignals = CopyOnWriteArrayList<Set<WorkspaceSignal>>()
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            observeWorkspaceEvents = { _, _, observer ->
                observer(WorkspaceSignal.Source)
                AutoCloseable {}
            },
            refreshWorkspace = { _, _, signals -> refreshedSignals += signals },
            runProjectIndexing = { _, _ -> },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(),
            waitForNextPass = { false },
            resolveWorkspaceStateIdentity = { WorkspaceStateIdentity("stable") },
        )

        try {
            indexing.start()
            indexing.awaitTermination()

            assertEquals(listOf(setOf(WorkspaceSignal.Source, WorkspaceSignal.BuildSemantic)), refreshedSignals)
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    @Test
    fun `initial build refresh targets the resolved Gradle root for a nested workspace`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val buildRoot = tempDir.resolve("nested-build").also { root ->
            root.toFile().mkdirs()
            root.resolve("settings.gradle.kts").toFile().writeText("rootProject.name = \"demo\"")
        }
        val workspaceRoot = buildRoot.resolve("modules/app").also { it.toFile().mkdirs() }
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val refreshedRoots = CopyOnWriteArrayList<Path>()
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            observeWorkspaceEvents = { _, _, _ -> AutoCloseable {} },
            refreshWorkspace = { _, refreshRoot, _ -> refreshedRoots.add(refreshRoot) },
            runProjectIndexing = { _, _ -> },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(),
            waitForNextPass = { false },
            resolveWorkspaceStateIdentity = { WorkspaceStateIdentity("stable") },
        )

        try {
            indexing.start()
            indexing.awaitTermination()

            assertEquals(listOf(buildRoot.toRealPath()), refreshedRoots)
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    @Test
    fun `event during refresh invalidates the production pass before publication`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = tempDir.resolve("moving-refresh")
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val observed = AtomicReference<(WorkspaceSignal) -> Unit>()
        val refreshPass = AtomicInteger()
        val publishedPasses = mutableListOf<Int>()
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            observeWorkspaceEvents = { _, _, observer ->
                observed.set(observer)
                AutoCloseable {}
            },
            refreshWorkspace = { _, _, _ ->
                if (refreshPass.incrementAndGet() == 1) {
                    observed.get().invoke(WorkspaceSignal.Source)
                }
            },
            runProjectIndexing = { _, _ -> },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(
                onCommit = { publishedPasses += refreshPass.get() },
            ),
            waitForNextPass = { false },
            resolveWorkspaceStateIdentity = { WorkspaceStateIdentity("stable") },
        )

        try {
            indexing.start()
            indexing.awaitTermination()

            assertEquals(listOf(2), publishedPasses)
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    @Test
    fun `cancellation during reconciliation cannot publish ready`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, tempDir.resolve("cancelled-reconciliation"))
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val admission = readyAdmission(project)
        val publications = mutableListOf<WorkspaceStateIdentity>()
        lateinit var indexing: KastIdeaProjectIndexing
        indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            indexStore = store,
            semanticAdmission = admission,
            observeWorkspaceEvents = { _, _, _ -> AutoCloseable {} },
            refreshWorkspace = { _, _, _ -> },
            runProjectIndexing = { _, _ -> indexing.cancel() },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(onCommit = publications::add),
            waitForNextPass = { false },
            resolveWorkspaceStateIdentity = { WorkspaceStateIdentity("cancelled-candidate") },
        )

        try {
            indexing.start()
            indexing.awaitTermination()

            assertTrue(publications.isEmpty())
            assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)
        } finally {
            indexing.cancel()
            store.close()
        }
    }

    private fun readyAdmission(project: Project): IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(
        project = project,
        inspectProject = { IdeaIndexSemanticAdmission.Inspection.Ready },
    )
}
