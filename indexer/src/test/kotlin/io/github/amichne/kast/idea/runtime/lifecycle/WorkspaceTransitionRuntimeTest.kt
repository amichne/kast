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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
            publishWorkspaceGeneration = { publishedPasses += refreshPass.get() },
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

    private fun readyAdmission(project: Project): IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(
        project = project,
        inspectProject = { IdeaIndexSemanticAdmission.Inspection.Ready },
    )
}
