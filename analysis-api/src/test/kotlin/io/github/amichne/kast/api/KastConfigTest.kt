package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
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
    fun serverMaxResultsExposesSectionKeyAndTypedDefault() {
        val maxResults = KastConfig.defaults().server.maxResults

        assertEquals("server", maxResults.section)
        assertEquals("maxResults", maxResults.key)
        assertEquals(ConfigurationDefault(500), maxResults.default)
        assertEquals(500, maxResults.value)
    }

    @Test
    fun phase2ParallelismExposesRaisedTypedDefault() {
        val phase2Parallelism = KastConfig.defaults().indexing.phase2Parallelism

        assertEquals("indexing", phase2Parallelism.section)
        assertEquals("phase2Parallelism", phase2Parallelism.key)
        assertEquals(ConfigurationDefault(4), phase2Parallelism.default)
        assertEquals(4, phase2Parallelism.value)
    }

    @Test
    fun phase2PriorityDepthExposesTypedDefault() {
        val phase2PriorityDepth = KastConfig.defaults().indexing.phase2PriorityDepth

        assertEquals("indexing", phase2PriorityDepth.section)
        assertEquals("phase2PriorityDepth", phase2PriorityDepth.key)
        assertEquals(ConfigurationDefault(2), phase2PriorityDepth.default)
        assertEquals(2, phase2PriorityDepth.value)
    }

    @Test
    fun `defaults expose paths and cli sections`() {
        val configFields = KastConfig::class.java.declaredFields.map { it.name }.toSet()

        assertTrue("runtime" in configFields)
        assertTrue("paths" in configFields)
        assertTrue("cli" in configFields)
    }

    @Test
    fun `defaults expose runtime field defaults`() {
        val config = KastConfig.defaults()

        assertEquals("runtime", config.runtime.defaultBackend.section)
        assertEquals("defaultBackend", config.runtime.defaultBackend.key)
        assertEquals(ConfigurationDefault("auto"), config.runtime.defaultBackend.default)
        assertEquals("auto", config.runtime.defaultBackend.value)
        assertEquals("runtime.ideaLaunch", config.runtime.ideaLaunch.enabled.section)
        assertEquals("enabled", config.runtime.ideaLaunch.enabled.key)
        assertEquals(false, config.runtime.ideaLaunch.enabled.value)
        assertEquals("idea", config.runtime.ideaLaunch.command.value)
        assertEquals(90_000L, config.runtime.ideaLaunch.waitTimeoutMillis.value)
        assertEquals(true, config.runtime.ideaLaunch.requireInstalledPlugin.value)
    }

    @Test
    fun `defaults expose project open field defaults`() {
        val config = KastConfig.defaults()

        assertEquals("projectOpen", config.projectOpen.profileAutoInit.section)
        assertEquals("profileAutoInit", config.projectOpen.profileAutoInit.key)
        assertEquals(ConfigurationDefault(false), config.projectOpen.profileAutoInit.default)
        assertEquals(false, config.projectOpen.profileAutoInit.value)
        assertEquals("copilot-lsp", config.projectOpen.profile.value)
        assertEquals(true, config.projectOpen.autoExcludeGit.value)
    }

    @Test
    fun `defaults expose path and cli field defaults`() {
        val config = KastConfig.defaults()
        val installRoot = Path.of(System.getProperty("user.home")).resolve(".local/share/kast")
        val binDir = Path.of(System.getProperty("user.home")).resolve(".local/bin")
        val libDir = installRoot.resolve("current/lib")
        val cacheDir = Path.of(System.getProperty("user.home")).resolve(".cache/kast")
        val logsDir = Path.of(System.getProperty("user.home")).resolve(".local/state/kast/logs")
        val runtimeDir = installRoot.resolve("runtime")
        val descriptorDir = runtimeDir.resolve("daemons")
        val socketDir = runtimeDir
        val binaryPath = binDir.resolve("kast")
        val runtimeLibsDir = libDir.resolve("backends/headless/current/runtime-libs")

        assertEquals("paths", config.paths.installRoot.section)
        assertEquals("installRoot", config.paths.installRoot.key)
        assertEquals(ConfigurationDefault(installRoot.toString()), config.paths.installRoot.default)
        assertEquals(installRoot.toString(), config.paths.installRoot.value)
        assertEquals(binDir.toString(), config.paths.binDir.value)
        assertEquals(libDir.toString(), config.paths.libDir.value)
        assertEquals(cacheDir.toString(), config.paths.cacheDir.value)
        assertEquals(logsDir.toString(), config.paths.logsDir.value)
        assertEquals(runtimeDir.toString(), config.paths.runtimeDir.value)
        assertEquals(descriptorDir.toString(), config.paths.descriptorDir.value)
        assertEquals(socketDir.toString(), config.paths.socketDir.value)

        assertEquals("cli", config.cli.binaryPath.section)
        assertEquals("binaryPath", config.cli.binaryPath.key)
        assertEquals(binaryPath.toString(), config.cli.binaryPath.value)
        assertEquals(runtimeLibsDir.toString(), config.backends.headless.runtimeLibsDir.value.orNull)
        assertEquals(OptionalConfigString.Unset, config.backends.headless.ideaHome.value)
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
            "indexing" to "phase2Enabled",
            "indexing" to "phase2BatchSize",
            "indexing" to "phase2Parallelism",
            "indexing" to "phase2PriorityDepth",
            "indexing" to "identifierIndexWaitMillis",
            "indexing" to "referenceBatchSize",
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
            "runtime" to "defaultBackend",
            "runtime.ideaLaunch" to "enabled",
            "runtime.ideaLaunch" to "command",
            "runtime.ideaLaunch" to "waitTimeoutMillis",
            "runtime.ideaLaunch" to "requireInstalledPlugin",
            "projectOpen" to "profileAutoInit",
            "projectOpen" to "profile",
            "projectOpen" to "autoExcludeGit",
            "backends.headless" to "enabled",
            "backends.headless" to "runtimeLibsDir",
            "backends.headless" to "ideaHome",
            "backends.idea" to "enabled",
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
    fun `workspace directory resolver uses git remote worktree hierarchy when origin is parseable`() {
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = tempDir.resolve("workspace")
        val gitDir = tempDir.resolve("main.git").resolve("worktrees").resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { installRoot },
            gitWorkspaceResolver = {
                GitWorkspace(
                    toplevel = workspaceRoot,
                    commonDir = tempDir.resolve("main.git"),
                    gitDir = gitDir,
                    remote = GitRemote(host = "github.com", owner = "amichne", repo = "kast"),
                )
            },
        )

        val dataDirectory = resolver.workspaceDataDirectory(workspaceRoot)

        assertEquals(
            installRoot.resolve("state/workspaces/git/github.com/amichne/kast/worktrees/workspace--${gitWorktreeHash(workspaceRoot, gitDir)}"),
            dataDirectory,
        )
        assertEquals(dataDirectory.resolve("cache"), resolver.workspaceCacheDirectory(workspaceRoot))
        assertEquals(dataDirectory.resolve("cache/source-index.db"), resolver.workspaceDatabasePath(workspaceRoot))
    }

    @Test
    fun `workspace directory resolver persists local workspace ids when origin is unavailable`() {
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = Path.of("/workspace/not-git")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { installRoot },
            gitRemoteResolver = { null },
        )

        val first = resolver.workspaceDataDirectory(workspaceRoot)
        val second = resolver.workspaceDataDirectory(workspaceRoot)

        assertEquals(first, second)
        assertTrue(first.startsWith(installRoot.resolve("state/workspaces/local")))
        assertTrue(
            installRoot.resolve("state/workspaces/local-workspaces.json")
                .readText()
                .contains(workspaceRoot.toAbsolutePath().normalize().toString())
        )
    }

    @Test
    fun `config loader merges hardcoded defaults global config and workspace config`() {
        val configHome = tempDir.resolve("config-home")
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { installRoot },
            gitWorkspaceResolver = {
                GitWorkspace(
                    toplevel = workspaceRoot,
                    commonDir = tempDir.resolve("main.git"),
                    gitDir = tempDir.resolve("main.git").resolve("worktrees").resolve("workspace"),
                    remote = GitRemote(host = "github.com", owner = "amichne", repo = "kast"),
                )
            },
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

                [runtime]
                default-backend = "idea"

                [runtime.idea-launch]
                enabled = true
                command = "/Applications/IntelliJ IDEA.app/Contents/MacOS/idea"
                wait-timeout-millis = 12345
                require-installed-plugin = false

                [project-open]
                profile-auto-init = true
                profile = "copilot-lsp"
                auto-exclude-git = false

                [cache]
                enabled = false

                [indexing.remote]
                enabled = true
                source-index-url = "file:///tmp/kast/source-index.db"

                [backends.headless]
                idea-home = "/Applications/IDEA CE.app/Contents"
                """.trimIndent(),
            )
        }

        val config = KastConfig.load(
            workspaceRoot = workspaceRoot,
            configHome = { configHome },
            workspaceDirectoryResolver = resolver,
        )

        assertEquals(75, config.server.maxResults.value)
        assertEquals("idea", config.runtime.defaultBackend.value)
        assertEquals(true, config.runtime.ideaLaunch.enabled.value)
        assertEquals("/Applications/IntelliJ IDEA.app/Contents/MacOS/idea", config.runtime.ideaLaunch.command.value)
        assertEquals(12_345L, config.runtime.ideaLaunch.waitTimeoutMillis.value)
        assertEquals(false, config.runtime.ideaLaunch.requireInstalledPlugin.value)
        assertEquals(true, config.projectOpen.profileAutoInit.value)
        assertEquals("copilot-lsp", config.projectOpen.profile.value)
        assertEquals(false, config.projectOpen.autoExcludeGit.value)
        assertEquals(45_000L, config.server.requestTimeoutMillis.value)
        assertEquals(
            KastConfig.defaults().server.maxConcurrentRequests.value,
            config.server.maxConcurrentRequests.value
        )
        assertEquals(false, config.cache.enabled.value)
        assertEquals(true, config.indexing.remote.enabled.value)
        assertEquals("file:///tmp/kast/source-index.db", config.indexing.remote.sourceIndexUrl.value.orNull)
        assertEquals(OptionalConfigString.Unset, config.backends.headless.ideaHome.value)
        assertEquals(true, config.telemetry.enabled.value)
        assertEquals("references,rename", config.telemetry.scopes.value)
        assertEquals(config.server.maxResults.value, config.toServerLimits().maxResults)
        assertEquals(config.server.requestTimeoutMillis.value, config.toServerLimits().requestTimeoutMillis)
    }

    @Test
    fun `idea config loader ignores workspace path and binary overrides`() {
        val configHome = tempDir.resolve("config-home")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { tempDir.resolve("manifest-root") },
            gitWorkspaceResolver = {
                GitWorkspace(
                    toplevel = workspaceRoot,
                    commonDir = tempDir.resolve("main.git"),
                    gitDir = tempDir.resolve("main.git").resolve("worktrees").resolve("workspace"),
                    remote = GitRemote(host = "github.com", owner = "amichne", repo = "kast"),
                )
            },
        )
        configHome.resolve("config.toml").apply {
            parent.toFile().mkdirs()
            writeText(
                """
                [paths]
                installRoot = "/global/should-not-win"

                [cli]
                binaryPath = "/global/bin/kast"
                """.trimIndent(),
            )
        }
        resolver.workspaceDataDirectory(workspaceRoot).resolve("config.toml").apply {
            parent.toFile().mkdirs()
            writeText(
                """
                [paths]
                installRoot = "/workspace/should-not-win"
                cacheDir = "/workspace/cache"
                runtimeDir = "/workspace/runtime"
                descriptorDir = "/workspace/descriptors"
                socketDir = "/workspace/socket"

                [cli]
                binaryPath = "/workspace/bin/kast"

                [backends.headless]
                runtimeLibsDir = "/workspace/runtime-libs"
                ideaHome = "/workspace/idea-home"

                [runtime]
                defaultBackend = "idea"

                [projectOpen]
                profileAutoInit = true
                profile = "copilot-lsp"
                autoExcludeGit = false

                [backends.idea]
                enabled = false
                """.trimIndent(),
            )
        }

        val config = KastConfig.loadIdea(
            workspaceRoot = workspaceRoot,
            configHome = { configHome },
            workspaceDirectoryResolver = resolver,
        )

        val defaults = KastConfig.defaults()
        assertEquals(defaults.paths.installRoot.value, config.paths.installRoot.value)
        assertEquals(defaults.paths.cacheDir.value, config.paths.cacheDir.value)
        assertEquals(defaults.paths.runtimeDir.value, config.paths.runtimeDir.value)
        assertEquals(defaults.paths.descriptorDir.value, config.paths.descriptorDir.value)
        assertEquals(defaults.paths.socketDir.value, config.paths.socketDir.value)
        assertEquals(defaults.cli.binaryPath.value, config.cli.binaryPath.value)
        assertEquals(
            defaults.backends.headless.runtimeLibsDir.value.orNull,
            config.backends.headless.runtimeLibsDir.value.orNull,
        )
        assertEquals(OptionalConfigString.Unset, config.backends.headless.ideaHome.value)
        assertEquals("idea", config.runtime.defaultBackend.value)
        assertEquals(true, config.projectOpen.profileAutoInit.value)
        assertEquals(false, config.projectOpen.autoExcludeGit.value)
        assertEquals(false, config.backends.idea.enabled.value)
    }

    @Test
    fun `config loader does not depend on the thread context classloader`() {
        val configHome = tempDir.resolve("config-home")
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { installRoot },
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

        assertEquals(321, config.server.maxResults.value)
    }

    @Test
    fun `resolved runtime config json loads without reading toml install state`() {
        val runtimeConfig = tempDir.resolve("runtime-config.json").apply {
            writeText(
                """
                {
                  "server": {
                    "maxResults": 42,
                    "requestTimeoutMillis": 1234,
                    "maxConcurrentRequests": 7
                  },
                  "indexing": {
                    "phase2Enabled": false,
                    "phase2BatchSize": 11,
                    "phase2Parallelism": 2,
                    "phase2PriorityDepth": 1,
                    "identifierIndexWaitMillis": 9876,
                    "referenceBatchSize": 13,
                    "remote": {
                      "enabled": true,
                      "sourceIndexUrl": "file:///tmp/source-index.db"
                    }
                  },
                  "cache": {
                    "enabled": false,
                    "writeDelayMillis": 55,
                    "sourceIndexSaveDelayMillis": 66
                  },
                  "watcher": {
                    "debounceMillis": 77
                  },
                  "gradle": {
                    "toolingApiTimeoutMillis": 8888
                  },
                  "telemetry": {
                    "enabled": true,
                    "scopes": "references",
                    "detail": "debug",
                    "outputFile": "/tmp/telemetry.json"
                  },
                  "profiling": {
                    "enabled": true,
                    "modes": "cpu,alloc",
                    "durationSeconds": 99,
                    "outputDir": "/tmp/profiles",
                    "otlpEndpoint": "http://localhost:4317",
                    "emitManifest": false
                  },
                  "runtime": {
                    "defaultBackend": "headless",
                    "ideaLaunch": {
                      "enabled": true,
                      "command": "/usr/local/bin/idea",
                      "waitTimeoutMillis": 45678,
                      "requireInstalledPlugin": false
                    }
                  },
                  "projectOpen": {
                    "profileAutoInit": true,
                    "profile": "copilot-lsp",
                    "autoExcludeGit": false
                  },
                  "backends": {
                    "headless": {
                      "enabled": true,
                      "runtimeLibsDir": "/opt/kast/runtime-libs",
                      "ideaHome": "/opt/kast/idea-home"
                    },
                    "idea": {
                      "enabled": false
                    }
                  },
                  "paths": {
                    "installRoot": "/opt/kast",
                    "binDir": "/opt/kast/bin",
                    "libDir": "/opt/kast/lib",
                    "cacheDir": "/opt/kast/cache",
                    "logsDir": "/opt/kast/logs",
                    "runtimeDir": "/opt/kast/runtime",
                    "descriptorDir": "/opt/kast/cache/daemons",
                    "socketDir": "/tmp"
                  },
                  "cli": {
                    "binaryPath": "/opt/kast/bin/kast"
                  },
                  "install": {
                    "managedPaths": [
                      "lib/backends/headless/headless-v0.8.0",
                      "lib/backends/headless/current"
                    ]
                  }
                }
                """.trimIndent(),
            )
        }

        val config = KastConfig.loadResolvedJson(runtimeConfig)

        assertEquals(42, config.server.maxResults.value)
        assertEquals(1234L, config.server.requestTimeoutMillis.value)
        assertEquals(7, config.server.maxConcurrentRequests.value)
        assertEquals(false, config.indexing.phase2Enabled.value)
        assertEquals(11, config.indexing.phase2BatchSize.value)
        assertEquals(2, config.indexing.phase2Parallelism.value)
        assertEquals(1, config.indexing.phase2PriorityDepth.value)
        assertEquals(9876L, config.indexing.identifierIndexWaitMillis.value)
        assertEquals(13, config.indexing.referenceBatchSize.value)
        assertEquals(true, config.indexing.remote.enabled.value)
        assertEquals("file:///tmp/source-index.db", config.indexing.remote.sourceIndexUrl.value.orNull)
        assertEquals(false, config.cache.enabled.value)
        assertEquals(55L, config.cache.writeDelayMillis.value)
        assertEquals(66L, config.cache.sourceIndexSaveDelayMillis.value)
        assertEquals(77L, config.watcher.debounceMillis.value)
        assertEquals(8888L, config.gradle.toolingApiTimeoutMillis.value)
        assertEquals(true, config.telemetry.enabled.value)
        assertEquals("references", config.telemetry.scopes.value)
        assertEquals("debug", config.telemetry.detail.value)
        assertEquals("/tmp/telemetry.json", config.telemetry.outputFile.value.orNull)
        assertEquals(true, config.profiling.enabled.value)
        assertEquals("cpu,alloc", config.profiling.modes.value)
        assertEquals(99L, config.profiling.durationSeconds.value)
        assertEquals("/tmp/profiles", config.profiling.outputDir.value)
        assertEquals("http://localhost:4317", config.profiling.otlpEndpoint.value.orNull)
        assertEquals(false, config.profiling.emitManifest.value)
        assertEquals("headless", config.runtime.defaultBackend.value)
        assertEquals(true, config.runtime.ideaLaunch.enabled.value)
        assertEquals("/usr/local/bin/idea", config.runtime.ideaLaunch.command.value)
        assertEquals(45_678L, config.runtime.ideaLaunch.waitTimeoutMillis.value)
        assertEquals(false, config.runtime.ideaLaunch.requireInstalledPlugin.value)
        assertEquals(true, config.projectOpen.profileAutoInit.value)
        assertEquals("copilot-lsp", config.projectOpen.profile.value)
        assertEquals(false, config.projectOpen.autoExcludeGit.value)
        assertEquals("/opt/kast/runtime-libs", config.backends.headless.runtimeLibsDir.value.orNull)
        assertEquals("/opt/kast/idea-home", config.backends.headless.ideaHome.value.orNull)
        assertEquals(false, config.backends.idea.enabled.value)
        assertEquals("/opt/kast/cache", config.paths.cacheDir.value)
        assertEquals("/opt/kast/runtime", config.paths.runtimeDir.value)
        assertEquals("/opt/kast/bin/kast", config.cli.binaryPath.value)
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
}
