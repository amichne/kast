package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `defaults expose paths and cli sections`() {
        val configFields = KastConfig::class.java.declaredFields.map { it.name }.toSet()

        assertTrue("paths" in configFields)
        assertTrue("cli" in configFields)
    }

    @Test
    fun `removed phase two indexing keys fail config loading`() {
        tempDir.resolve("config.toml").writeText(
            """
                [indexing]
                phase2Parallelism = 2
            """.trimIndent(),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            KastConfig.loadGlobal(configHome = { tempDir })
        }

        assertTrue(error.message.orEmpty().contains("indexing.relationships.parallelism"))
    }

    @Test
    fun `defaults expose path and cli field defaults`() {
        val config = KastConfig.defaults()
        val resolved = resolveKastPathDefaults()

        assertEquals("paths", config.paths.installRoot.section)
        assertEquals("installRoot", config.paths.installRoot.key)
        assertEquals(ConfigurationDefault(resolved.installRoot.toString()), config.paths.installRoot.default)
        assertEquals(resolved.installRoot.toString(), config.paths.installRoot.value)
        assertEquals(resolved.binDir.toString(), config.paths.binDir.value)
        assertEquals(resolved.libDir.toString(), config.paths.libDir.value)
        assertEquals(resolved.cacheDir.toString(), config.paths.cacheDir.value)
        assertEquals(resolved.logsDir.toString(), config.paths.logsDir.value)
        assertEquals(resolved.runtimeDir.toString(), config.paths.runtimeDir.value)
        assertEquals(resolved.descriptorDir.toString(), config.paths.descriptorDir.value)
        assertEquals(resolved.socketDir.toString(), config.paths.socketDir.value)

        assertEquals("cli", config.cli.binaryPath.section)
        assertEquals("binaryPath", config.cli.binaryPath.key)
        assertEquals(resolved.cliBinary.toString(), config.cli.binaryPath.value)
    }

    @Test
    fun `defaults expose profiling field defaults`() {
        val config = KastConfig.defaults()

        assertEquals("profiling", config.profiling.enabled.section)
        assertEquals("enabled", config.profiling.enabled.key)
        assertEquals(false, config.profiling.enabled.value)
        assertEquals("cpu", config.profiling.modes.value)
        assertEquals(30L, config.profiling.durationSeconds.value)
        assertEquals("{logsDir}/profiling", config.profiling.outputDir.value)
        assertEquals(OptionalConfigString.Unset, config.profiling.otlpEndpoint.value)
        assertEquals(true, config.profiling.emitManifest.value)
    }

    @Test
    fun `configuration field section key pairs are unique and complete`() {
        val expectedFields = setOf(
            "server" to "maxResults",
            "server" to "requestTimeoutMillis",
            "server" to "maxConcurrentRequests",
            "indexing.relationships" to "enabled",
            "indexing.relationships" to "batchSize",
            "indexing.relationships" to "parallelism",
            "indexing.relationships" to "modulePriorityDepth",
            "indexing" to "criticalPaths",
            "indexing" to "ignoredPaths",
            "indexing.graph" to "batchSize",
            "indexing" to "identifierIndexWaitMillis",
            "indexing.remote" to "enabled",
            "indexing.remote" to "sourceIndexUrl",
            "cache" to "enabled",
            "cache" to "writeDelayMillis",
            "cache" to "sourceIndexSaveDelayMillis",
            "watcher" to "debounceMillis",
            "gradle" to "toolingApiTimeoutMillis",
            "telemetry" to "enabled",
            "telemetry" to "scopes",
            "telemetry" to "detail",
            "telemetry" to "outputFile",
            "profiling" to "enabled",
            "profiling" to "modes",
            "profiling" to "durationSeconds",
            "profiling" to "outputDir",
            "profiling" to "otlpEndpoint",
            "profiling" to "emitManifest",
            "codex.hooks" to "enabled",
            "codex.hooks" to "sessionStart",
            "codex.hooks" to "postToolUse",
            "paths" to "installRoot",
            "paths" to "binDir",
            "paths" to "libDir",
            "paths" to "cacheDir",
            "paths" to "logsDir",
            "paths" to "runtimeDir",
            "paths" to "descriptorDir",
            "paths" to "socketDir",
            "cli" to "binaryPath",
        )
        val actualFields = ConfigurationField.defaultFields().map { it.section to it.key }

        assertEquals(expectedFields, actualFields.toSet())
        assertEquals(actualFields.size, actualFields.toSet().size)
    }

    @Test
    fun `global codex hook settings default enabled and parse independently`() {
        tempDir.resolve("config.toml").writeText(
            """
                [codex.hooks]
                sessionStart = false
            """.trimIndent(),
        )

        val hooks = KastConfig.loadGlobal(configHome = { tempDir }).codex.hooks

        assertEquals(true, hooks.enabled.value)
        assertEquals(false, hooks.sessionStart.value)
        assertEquals(true, hooks.postToolUse.value)
    }

    @Test
    fun `git remote parser supports ssh and https origin urls`() {
        assertEquals(
            GitRemote(host = "github.com", owner = "amichne", repo = "kast"),
            GitRemoteParser.parse("  git@github.com:amichne/kast.git  "),
        )
        assertEquals(
            GitRemote(host = "github.com", owner = "amichne", repo = "kast"),
            GitRemoteParser.parse("https://github.com/amichne/kast.git"),
        )

        assertNull(GitRemoteParser.parse("not-a-git-origin"))
        listOf(
            "https:///amichne/kast.git",
            "https://github.com//kast.git",
            "https://github.com/amichne/",
        ).forEach { remote ->
            assertNull(GitRemoteParser.parse(remote), remote)
        }
    }

    @Test
    fun `workspace directory resolver uses stable common directory hierarchy when origin is parseable`() {
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = tempDir.resolve("workspace")
        val commonDir = tempDir.resolve("main.git")
        val gitDir = commonDir.resolve("worktrees").resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { installRoot },
            gitWorkspaceResolver = {
                GitWorkspace(
                    toplevel = workspaceRoot,
                    commonDir = commonDir,
                    gitDir = gitDir,
                    remote = GitRemote(host = "github.com", owner = "amichne", repo = "kast"),
                )
            },
        )

        val dataDirectory = resolver.workspaceDataDirectory(workspaceRoot)

        assertEquals(
            installRoot.resolve(
                "state/data/workspaces/git/local/${gitCommonDirHash(commonDir)}/worktrees/" +
                    "workspace--${gitWorktreeHash(workspaceRoot, gitDir)}",
            ),
            dataDirectory,
        )
        assertEquals(dataDirectory.resolve("cache"), resolver.workspaceCacheDirectory(workspaceRoot))
        assertEquals(dataDirectory.resolve("cache/source-index.db"), resolver.workspaceDatabasePath(workspaceRoot))
    }

    @Test
    fun `workspace directory resolver derives local workspace ids without persisting first resolution`() {
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = Path.of("/workspace/not-git")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { installRoot },
            gitWorkspaceResolver = { null },
        )

        val first = resolver.workspaceDataDirectory(workspaceRoot)
        val second = resolver.workspaceDataDirectory(workspaceRoot)

        assertEquals(first, second)
        assertTrue(first.startsWith(installRoot.resolve("state/data/workspaces/local")))
        assertTrue(!installRoot.resolve("state/data/workspaces/local-workspaces.json").toFile().exists())
    }

    @Test
    fun `config loader merges hardcoded defaults global config and workspace config`() = assertConfigLoaderMerging(tempDir)

    @Test
    fun `config loader does not depend on the thread context classloader`() {
        val configHome = tempDir.resolve("config-home")
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { installRoot },
            gitWorkspaceResolver = { null },
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

        assertEquals(321, config.server.maxResults.value)
    }

    @Test
    fun `resolved runtime config json loads without reading toml install state`() = assertResolvedRuntimeConfigLoading(tempDir)

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

}
