package io.github.amichne.kast.api.client

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class KastConfigTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `git remote parser supports ssh and https origin urls`() {
        assertEquals(
            GitRemote(host = "github.com", owner = "amichne", repo = "kast"),
            GitRemoteParser.parse("git@github.com:amichne/kast.git"),
        )
        assertEquals(
            GitRemote(host = "github.com", owner = "amichne", repo = "kast"),
            GitRemoteParser.parse("https://github.com/amichne/kast.git"),
        )

        assertNull(GitRemoteParser.parse("not-a-git-origin"))
    }

    @Test
    fun `workspace directory resolver uses git remote hierarchy when origin is parseable`() {
        val configHome = tempDir.resolve("config-home")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            configHome = { configHome },
            gitRemoteResolver = { GitRemote(host = "github.com", owner = "amichne", repo = "kast") },
        )

        val dataDirectory = resolver.workspaceDataDirectory(workspaceRoot)

        assertEquals(
            configHome.resolve("workspaces/github.com/amichne/kast/${resolver.workspaceHash(workspaceRoot)}"),
            dataDirectory,
        )
        assertEquals(dataDirectory.resolve("cache"), resolver.workspaceCacheDirectory(workspaceRoot))
        assertEquals(dataDirectory.resolve("cache/source-index.db"), resolver.workspaceDatabasePath(workspaceRoot))
    }

    @Test
    fun `workspace directory resolver persists local workspace ids when origin is unavailable`() {
        val configHome = tempDir.resolve("config-home")
        val workspaceRoot = tempDir.resolve("not-git")
        val resolver = WorkspaceDirectoryResolver(
            configHome = { configHome },
            gitRemoteResolver = { null },
        )

        val first = resolver.workspaceDataDirectory(workspaceRoot)
        val second = resolver.workspaceDataDirectory(workspaceRoot)

        assertEquals(first, second)
        assertTrue(first.startsWith(configHome.resolve("workspaces/local")))
        assertTrue(configHome.resolve("local-workspaces.json").readText().contains(workspaceRoot.toAbsolutePath().normalize().toString()))
    }

    @Test
    fun `config loader merges hardcoded defaults global config and workspace config`() {
        val configHome = tempDir.resolve("config-home")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            configHome = { configHome },
            gitRemoteResolver = { GitRemote(host = "github.com", owner = "amichne", repo = "kast") },
        )
        configHome.resolve("config.toml").apply {
            parent.toFile().mkdirs()
            writeText(
                """
                [server]
                max-results = 1200
                request-timeout-millis = 45000

                [telemetry]
                enabled = true
                scopes = "references,rename"
                """.trimIndent(),
            )
        }
        resolver.workspaceDataDirectory(workspaceRoot).resolve("config.toml").apply {
            parent.toFile().mkdirs()
            writeText(
                """
                [server]
                max-results = 75

                [cache]
                enabled = false

                [indexing.remote]
                enabled = true
                source-index-url = "file:///tmp/kast/source-index.db"
                """.trimIndent(),
            )
        }

        val config = KastConfig.load(
            workspaceRoot = workspaceRoot,
            configHome = { configHome },
            workspaceDirectoryResolver = resolver,
        )

        assertEquals(75, config.server.maxResults)
        assertEquals(45_000L, config.server.requestTimeoutMillis)
        assertEquals(KastConfig.defaults().server.maxConcurrentRequests, config.server.maxConcurrentRequests)
        assertEquals(false, config.cache.enabled)
        assertEquals(true, config.indexing.remote.enabled)
        assertEquals("file:///tmp/kast/source-index.db", config.indexing.remote.sourceIndexUrl)
        assertEquals(true, config.telemetry.enabled)
        assertEquals("references,rename", config.telemetry.scopes)
        assertEquals(config.server.maxResults, config.toServerLimits().maxResults)
        assertEquals(config.server.requestTimeoutMillis, config.toServerLimits().requestTimeoutMillis)
    }

    @Test
    fun `config loader uses Kast classloader instead of thread context loader for default Hoplite services`() {
        val configHome = tempDir.resolve("config-home")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            configHome = { configHome },
            gitRemoteResolver = { GitRemote(host = "github.com", owner = "amichne", repo = "kast") },
        )
        configHome.resolve("config.toml").apply {
            parent.toFile().mkdirs()
            writeText(
                """
                [server]
                max-results = 321
                """.trimIndent(),
            )
        }

        val config = URLClassLoader(emptyArray(), null).use { emptyContextClassLoader ->
            withContextClassLoader(emptyContextClassLoader) {
                KastConfig.load(
                    workspaceRoot = workspaceRoot,
                    configHome = { configHome },
                    workspaceDirectoryResolver = resolver,
                )
            }
        }

        assertEquals(321, config.server.maxResults)
    }

    @Test
    fun `config loader does not write Hoplite warnings to stdout`() {
        val configHome = tempDir.resolve("config-home")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            configHome = { configHome },
            gitRemoteResolver = { GitRemote(host = "github.com", owner = "amichne", repo = "kast") },
        )
        configHome.resolve("config.toml").apply {
            parent.toFile().mkdirs()
            writeText(
                """
                [server]
                max-results = 321
                """.trimIndent(),
            )
        }

        val stdout = captureStandardOut {
            val config = KastConfig.load(
                workspaceRoot = workspaceRoot,
                configHome = { configHome },
                workspaceDirectoryResolver = resolver,
            )
            assertEquals(321, config.server.maxResults)
        }

        assertTrue(stdout.isBlank(), "Expected KastConfig.load to keep stdout clean, but got: $stdout")
    }

    private fun <T> withContextClassLoader(
        classLoader: ClassLoader,
        block: () -> T,
    ): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = classLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private fun <T> captureStandardOut(block: () -> T): String {
        val original = System.out
        val output = ByteArrayOutputStream()
        System.setOut(PrintStream(output, true, Charsets.UTF_8))
        return try {
            block()
            output.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }
}
