package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.RuntimeInstanceId
import io.github.amichne.kast.api.client.ServerInstanceOwnership
import io.github.amichne.kast.api.client.fields.GraphIndexingBatchSize
import io.github.amichne.kast.api.client.fields.PathsDescriptorDir
import io.github.amichne.kast.api.client.fields.PathsLogsDir
import io.github.amichne.kast.api.client.fields.PathsSocketDir
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.indexer.gradle.bootstrap.InitialProjectModelAuthority
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStoreAccess
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class IndexerServerRuntimeTest {
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
    fun `managed runtime descriptor uses its registration identity`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = targetFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).parent.toAbsolutePath().normalize()
        val socketPath = tempDir.resolve("kast-indexer.sock")
        val descriptorDirectory = tempDir.resolve("descriptors")
        val runtimeInstanceId = RuntimeInstanceId.parse("550e8400-e29b-41d4-a716-446655440000")
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

        IndexerServerRuntime.startWithRegistrationProof(
            project = project,
            workspaceRoot = workspaceRoot,
            transport = AnalysisTransport.UnixDomainSocket(socketPath),
            config = config,
            registrationProof = null,
            runtimeInstanceId = runtimeInstanceId,
            initialProjectModelAuthority = InitialProjectModelAuthority.Unverified,
        ).use { runtime ->
            assertEquals("indexer", runtime.backend.capabilities().backendName)
            assertEquals("indexer", runtime.backend.runtimeStatus().backendName)
            val delegateField = runtime.backend.javaClass.getDeclaredField("delegate").apply { isAccessible = true }
            val pluginBackend = delegateField.get(runtime.backend) as KastIndexerBackend
            assertEquals(GraphIndexingBatchSize(7), pluginBackend.semanticGraphBatchSize)
            pluginBackend.updateSemanticGraphBatchSize(GraphIndexingBatchSize(9))
            assertEquals(GraphIndexingBatchSize(9), pluginBackend.semanticGraphBatchSize)
            assertEquals(socketPath.toRealPath(), runtime.server.descriptor?.socketPath?.toPath())
            val ownership = requireNotNull(runtime.server.descriptor).ownership as ServerInstanceOwnership.Owned
            assertEquals(runtimeInstanceId, ownership.runtimeInstanceId)
            assertTrue(descriptorDirectory.resolve("daemons.json").exists())
        }
    }

    @Test
    fun `runtime opens one workspace database without filesystem generations`() = runBlocking {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = tempDir.resolve("restart-recovery-workspace")
        Files.createDirectories(workspaceRoot)
        val socketPath = tempDir.resolve("restart-recovery.sock")
        val descriptorDirectory = tempDir.resolve("restart-recovery-descriptors")
        val config = KastConfig.defaults().let { defaults ->
            defaults.copy(
                paths = defaults.paths.copy(
                    descriptorDir = PathsDescriptorDir(descriptorDirectory.toString()),
                    logsDir = PathsLogsDir(tempDir.resolve("restart-recovery-logs").toString()),
                    socketDir = PathsSocketDir(tempDir.toString()),
                ),
            )
        }
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(
            project = project,
            workspaceRoot = workspaceRoot,
            descriptorDirectory = descriptorDirectory,
        ).workspaceIdentity
        val database = workspaceIdentity.sourceIndexDatabaseFile

        try {
            IndexerServerRuntime.start(
                project = project,
                workspaceRoot = workspaceRoot,
                socketPath = socketPath,
                config = config,
                startProjectIndexing = false,
            ).use { runtime ->
                assertEquals("indexer", runtime.backend.capabilities().backendName)
            }

            SqliteSourceIndexStore(workspaceIdentity, SqliteSourceIndexStoreAccess.READ_ONLY).use { recovered ->
                assertEquals(0, recovered.readGeneration().value)
            }
            assertTrue(Files.isRegularFile(database))
            assertFalse(Files.exists(workspaceIdentity.workspaceDataDirectoryPath.resolve("semantic-generations")))
        } finally {
            workspaceIdentity.workspaceDataDirectoryPath.toFile().deleteRecursively()
        }
    }
}
