package io.github.amichne.kast.headless

import com.intellij.openapi.application.ApplicationStarter
import io.github.amichne.kast.api.contract.AnalysisTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class HeadlessServerOptionsTest {
    @Test
    fun `headless starter implements IDEA app starter extension type`() {
        assertEquals(Any::class.java, HeadlessApplicationStarter::class.java.superclass)
        assertTrue(HeadlessApplicationStarter::class.java.interfaces.contains(ApplicationStarter::class.java))
    }

    @Test
    fun `starter args drop command token and preserve existing server options`() {
        val options = HeadlessServerOptions.parseStarterArgs(
            listOf(
                HeadlessApplicationStarter.COMMAND_NAME,
                "--workspace-root=/tmp/project",
                "--socket-path=/tmp/kast-headless.sock",
                "--smoke-only",
                "--idea-home=/opt/idea",
            ),
        )

        assertEquals(Path.of("/tmp/project"), options.serverOptions.workspaceRoot)
        assertEquals(
            Path.of("/tmp/kast-headless.sock"),
            (options.serverOptions.transport as AnalysisTransport.UnixDomainSocket).socketPath,
        )
        assertTrue(options.smokeOnly)
    }

    @Test
    fun `main forwards args through idea command starter`() {
        val args = HeadlessRuntime.ideaMainArgs(arrayOf("--workspace-root=/tmp/project"))

        assertEquals(HeadlessApplicationStarter.COMMAND_NAME, args.first())
        assertEquals("--workspace-root=/tmp/project", args.last())
    }

    @Test
    fun `main args strip idea home before IDEA starter receives server options`() {
        val args = HeadlessRuntime.ideaMainArgs(
            arrayOf("--idea-home=/opt/idea", "--workspace-root=/tmp/project"),
        )

        assertEquals(listOf(HeadlessApplicationStarter.COMMAND_NAME, "--workspace-root=/tmp/project"), args.toList())
    }

    @Test
    fun `headless config override reads launcher path properties`() {
        withSystemProperties(
            HeadlessConfigProperties.CACHE_DIR to "/tmp/kast-cache",
            HeadlessConfigProperties.LOGS_DIR to "/tmp/kast-logs",
            HeadlessConfigProperties.DESCRIPTOR_DIR to "/tmp/kast-descriptors",
            HeadlessConfigProperties.SOCKET_DIR to "/tmp/kast-sockets",
        ) {
            val paths = HeadlessConfigProperties.configOverride(profilingOverride = null).paths

            assertEquals("/tmp/kast-cache", paths?.cacheDir?.value)
            assertEquals("/tmp/kast-logs", paths?.logsDir?.value)
            assertEquals("/tmp/kast-descriptors", paths?.descriptorDir?.value)
            assertEquals("/tmp/kast-sockets", paths?.socketDir?.value)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `project open task skips IDE startup work before server registration`() {
        val task = HeadlessProjectOpener.openProjectTask()

        assertEquals(false, task.isRefreshVfsNeeded)
        assertEquals(false, task.runConfigurators)
        assertEquals(false, task.runConversionBeforeOpen)
        assertEquals(false, task.preloadServices)
    }

    private fun withSystemProperties(
        vararg values: Pair<String, String>,
        block: () -> Unit,
    ) {
        val previousValues = values.associate { (key, _) -> key to System.getProperty(key) }
        try {
            values.forEach { (key, value) -> System.setProperty(key, value) }
            block()
        } finally {
            previousValues.forEach { (key, value) ->
                if (value == null) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, value)
                }
            }
        }
    }
}
