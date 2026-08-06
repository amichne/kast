package io.github.amichne.kast.indexer

import com.intellij.openapi.application.ApplicationStarter
import io.github.amichne.kast.api.client.RuntimeInstanceId
import io.github.amichne.kast.api.contract.AnalysisTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
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

        assertEquals(ApplicationStarter.NOT_IN_EDT, starter.requiredModality)
        starter.main(listOf(KastIndexerApplicationStarter.COMMAND_NAME, "--workspace-root=/tmp/project"))

        assertSame(caller, runtimeThread)
    }

    @Test
    fun `starter args drop command token and preserve existing server options`() {
        val runtimeInstanceId = RuntimeInstanceId.parse("550e8400-e29b-41d4-a716-446655440000")
        val options = IndexerServerOptions.parseStarterArgs(
            listOf(
                KastIndexerApplicationStarter.COMMAND_NAME,
                "--workspace-root=/tmp/project",
                "--socket-path=/tmp/kast-indexer.sock",
                "--runtime-instance-id=${runtimeInstanceId.value}",
                "--smoke-only",
                "--idea-home=/opt/idea",
            ),
        )

        assertEquals(Path.of("/tmp/project"), options.serverOptions.workspaceRoot)
        assertEquals(
            Path.of("/tmp/kast-indexer.sock"),
            (options.serverOptions.transport as AnalysisTransport.UnixDomainSocket).socketPath,
        )
        assertEquals(runtimeInstanceId, options.serverOptions.runtimeInstanceId)
        assertTrue(options.smokeOnly)
    }

    @Test
    fun `starter args load rust resolved runtime config file`() {
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
                "--workspace-root=/tmp/project",
                "--runtime-config-file=$runtimeConfig",
            ),
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
        val runtimeConfig = tempDir.resolve("runtime-config.json").apply {
            writeText("{}")
        }

        val options = IndexerServerOptions.parseStarterArgs(
            listOf(
                KastIndexerApplicationStarter.COMMAND_NAME,
                "--workspace-root=/tmp/project",
                "--runtime-config-file=$runtimeConfig",
                "--profile",
                "--profile-modes=cpu,alloc",
                "--profile-duration=12",
            ),
        )

        assertEquals(true, options.runtimeConfig?.profiling?.enabled?.value)
        assertEquals("cpu,alloc", options.runtimeConfig?.profiling?.modes?.value)
        assertEquals(12L, options.runtimeConfig?.profiling?.durationSeconds?.value)
    }
}
