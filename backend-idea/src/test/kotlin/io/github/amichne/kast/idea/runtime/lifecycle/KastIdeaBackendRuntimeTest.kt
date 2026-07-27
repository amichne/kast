package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.PathsDescriptorDir
import io.github.amichne.kast.api.client.fields.PathsLogsDir
import io.github.amichne.kast.api.client.fields.PathsSocketDir
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
            backendName = "headless",
        ).use { runtime ->
            assertEquals("headless", runtime.backend.capabilities().backendName)
            assertEquals("headless", runtime.backend.runtimeStatus().backendName)
            assertEquals(socketPath, runtime.server.descriptor?.socketPath?.let(Path::of))
            assertTrue(descriptorDirectory.resolve("daemons.json").exists())
        }
    }
}
