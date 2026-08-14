package io.github.amichne.kast.idea.backend

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.idea.TestWorkspaceSemanticReadAuthority
import io.github.amichne.kast.idea.TestWorkspaceTransitionRequester
import io.github.amichne.kast.idea.testPublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@TestApplication
class KastIndexerBackendRuntimeStatusTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
    }

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `runtime status exposes exact admitted generation only while ready`() {
        val published = testPublishedWorkspaceGeneration(
            generation = WorkspaceSemanticGeneration(7),
            identity = WorkspaceStateIdentity("verified-workspace"),
        )
        val readyBackend = backend(
            TestWorkspaceSemanticReadAuthority {
                IdeaIndexSemanticAdmission.Status.Ready(published)
            },
        )
        val pendingBackend = backend(
            TestWorkspaceSemanticReadAuthority {
                IdeaIndexSemanticAdmission.Status.Pending("workspace changed")
            },
        )

        readyBackend.use { ready ->
            pendingBackend.use { pending ->
                val readyStatus = runBlocking { ready.runtimeStatus() }
                val pendingStatus = runBlocking { pending.runtimeStatus() }

                assertEquals(7, readyStatus.publishedWorkspaceGeneration?.generation)
                assertEquals("verified-workspace", readyStatus.publishedWorkspaceGeneration?.identity)
                assertEquals("source-index.db", readyStatus.publishedWorkspaceGeneration?.databaseFile)
                assertNull(pendingStatus.publishedWorkspaceGeneration)
            }
        }
    }

    private fun backend(authority: TestWorkspaceSemanticReadAuthority): KastIndexerBackend = KastIndexerBackend(
        project = projectFixture.get(),
        workspaceRoot = tempDir,
        limits = ServerLimits(maxResults = 100, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
        workspaceSemanticReadAuthority = authority,
        workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
    )
}
