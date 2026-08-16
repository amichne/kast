package io.github.amichne.kast.indexer

import com.intellij.openapi.application.ApplicationStarter
import io.github.amichne.kast.api.contract.AnalysisTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.writeText

class KastIndexerLaunchOptionsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `indexer starter implements IDEA app starter extension type`() {
        assertEquals(Any::class.java, KastIndexerApplicationStarter::class.java.superclass)
        assertTrue(KastIndexerApplicationStarter::class.java.interfaces.contains(ApplicationStarter::class.java))
    }

    @Test
    fun `private payload exposes only the indexer starter and Kotlin mode`() {
        val pluginXml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))

        assertTrue(pluginXml.contains("KastIndexerApplicationStarter"))
        assertEquals(1, Regex("supportsKotlinPluginMode").findAll(pluginXml).count())
        assertTrue(!pluginXml.contains("projectService"))
        assertTrue(!pluginXml.contains("postStartupActivity"))
    }

    @Test
    fun `starter owns runtime until it stops`() {
        val caller = Thread.currentThread()
        var runtimeThread: Thread? = null
        val starter = KastIndexerApplicationStarter {
            runtimeThread = Thread.currentThread()
        }
        val workspace = workspace()

        assertEquals(ApplicationStarter.NOT_IN_EDT, starter.requiredModality)
        starter.main(
            listOf(KastIndexerApplicationStarter.COMMAND_NAME, "--workspace-root=$workspace") + layoutArgs(),
        )

        assertSame(caller, runtimeThread)
    }

    @Test
    fun `starter args drop command token and preserve existing server options`() {
        val workspace = workspace()
        val options = IndexerServerOptions.parseStarterArgs(
            listOf(
                KastIndexerApplicationStarter.COMMAND_NAME,
                "--workspace-root=$workspace",
                "--socket-path=/tmp/kast-indexer.sock",
                "--smoke-only",
                "--idea-home=/opt/idea",
            ) + layoutArgs(),
        )

        assertEquals(workspace, options.serverOptions.workspaceRoot)
        assertEquals(
            Path.of("/tmp/kast-indexer.sock"),
            (options.serverOptions.transport as AnalysisTransport.UnixDomainSocket).socketPath,
        )
        assertTrue(options.smokeOnly)
    }

    @Test
    fun `starter args load rust resolved runtime config file`() {
        val workspace = workspace()
        val runtimeConfig = tempDir.resolve("runtime-config.json").apply {
            writeText(
                """
                {
                  "server": {
                    "maxResults": 42,
                    "requestTimeoutMillis": 1234,
                    "maxConcurrentRequests": 7
                  }
                }
                """.trimIndent(),
            )
        }

        val options = IndexerServerOptions.parseStarterArgs(
            listOf(
                KastIndexerApplicationStarter.COMMAND_NAME,
                "--workspace-root=$workspace",
                "--runtime-config-file=$runtimeConfig",
            ) + layoutArgs(),
        )

        assertEquals(42, options.serverOptions.maxResults)
        assertEquals(1234L, options.serverOptions.requestTimeoutMillis)
        assertEquals(7, options.serverOptions.maxConcurrentRequests)
        assertNotNull(options.runtimeConfig)
    }

    @Test
    fun `main forwards args through idea command starter`() {
        val args = KastIndexerRuntime.ideaMainArgs(arrayOf("--workspace-root=/tmp/project"))

        assertEquals(KastIndexerApplicationStarter.COMMAND_NAME, args.first())
        assertEquals("--workspace-root=/tmp/project", args.last())
    }

    @Test
    fun `main args strip idea home before IDEA starter receives server options`() {
        val args = KastIndexerRuntime.ideaMainArgs(
            arrayOf("--idea-home=/opt/idea", "--workspace-root=/tmp/project"),
        )

        assertEquals(listOf(KastIndexerApplicationStarter.COMMAND_NAME, "--workspace-root=/tmp/project"), args.toList())
    }

    @Test
    fun `starter args apply launch profiling override to resolved runtime config`() {
        val workspace = workspace()
        val runtimeConfig = tempDir.resolve("runtime-config.json").apply {
            writeText("{}")
        }

        val options = IndexerServerOptions.parseStarterArgs(
            listOf(
                KastIndexerApplicationStarter.COMMAND_NAME,
                "--workspace-root=$workspace",
                "--runtime-config-file=$runtimeConfig",
                "--profile",
                "--profile-modes=cpu,alloc",
                "--profile-duration=12",
            ) + layoutArgs(),
        )

        assertEquals(true, options.runtimeConfig?.profiling?.enabled?.value)
        assertEquals("cpu,alloc", options.runtimeConfig?.profiling?.modes?.value)
        assertEquals(12L, options.runtimeConfig?.profiling?.durationSeconds?.value)
    }

    private fun workspace(): Path = tempDir.resolve("project").also { Files.createDirectories(it) }

    private fun layoutArgs(): List<String> {
        val workspace = workspace().toRealPath()
        val storageRoot = tempDir.resolve("kast-storage").also { Files.createDirectories(it) }.toRealPath()
        val workspaceData = tempDir.resolve("workspace-data").also { Files.createDirectories(it) }.toRealPath()
        val manifest = buildJsonObject {
            put("schemaVersion", 1)
            put("canonicalWorkspaceRoot", workspace.toString())
            put("canonicalStorageRoot", storageRoot.toString())
            put("workspaceDataDirectory", workspaceData.toString())
        }
        Files.writeString(storageRoot.resolve("launch-manifest.json"), manifest.toString())
        return listOf(
            "--indexer-storage-root=$storageRoot",
            "--storage-lease-fd=0",
            "--bootstrap-token=123e4567-e89b-42d3-a456-426614174000",
        )
    }
}
