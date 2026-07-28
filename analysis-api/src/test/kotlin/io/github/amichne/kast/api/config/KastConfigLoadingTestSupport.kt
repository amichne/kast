package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Path
import kotlin.io.path.writeText

internal fun assertConfigLoaderMerging(tempDir: Path) {
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
            profile = "jetbrains-plugin"
            auto-exclude-git = false
            gradle-load-enabled = false

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
    assertEquals(true, config.projectOpen.profileAutoInit.value)
    assertEquals("jetbrains-plugin", config.projectOpen.profile.value)
    assertEquals(ProjectOpenProfileKind.JETBRAINS_PLUGIN, config.projectOpen.profile.kind)
    assertEquals(false, config.projectOpen.autoExcludeGit.value)
    assertEquals(false, config.projectOpen.gradleLoadEnabled.value)
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

internal fun assertIdeaConfigIsolation(tempDir: Path) {
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

            [server]
            maxResults = 75

            [indexing]
            phase2Parallelism = 2

            [cache]
            enabled = false

            [projectOpen]
            profileAutoInit = true
            profile = "jetbrains-plugin"
            autoExcludeGit = false
            gradleLoadEnabled = false

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
    assertEquals(75, config.server.maxResults.value)
    assertEquals(2, config.indexing.phase2Parallelism.value)
    assertEquals(false, config.cache.enabled.value)
    assertEquals(true, config.projectOpen.profileAutoInit.value)
    assertEquals(ProjectOpenProfileKind.JETBRAINS_PLUGIN, config.projectOpen.profile.kind)
    assertEquals(false, config.projectOpen.autoExcludeGit.value)
    assertEquals(false, config.projectOpen.gradleLoadEnabled.value)
    assertEquals(false, config.backends.idea.enabled.value)

}

internal fun assertResolvedRuntimeConfigLoading(tempDir: Path) {
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
                  "waitTimeoutMillis": 45678
                }
              },
              "projectOpen": {
                "profileAutoInit": true,
                "profile": "jetbrains-plugin",
                "autoExcludeGit": false,
                "gradleLoadEnabled": false
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
    assertEquals(true, config.projectOpen.profileAutoInit.value)
    assertEquals("jetbrains-plugin", config.projectOpen.profile.value)
    assertEquals(ProjectOpenProfileKind.JETBRAINS_PLUGIN, config.projectOpen.profile.kind)
    assertEquals(false, config.projectOpen.autoExcludeGit.value)
    assertEquals(false, config.projectOpen.gradleLoadEnabled.value)
    assertEquals("/opt/kast/runtime-libs", config.backends.headless.runtimeLibsDir.value.orNull)
    assertEquals("/opt/kast/idea-home", config.backends.headless.ideaHome.value.orNull)
    assertEquals(false, config.backends.idea.enabled.value)
    assertEquals("/opt/kast/cache", config.paths.cacheDir.value)
    assertEquals("/opt/kast/runtime", config.paths.runtimeDir.value)
    assertEquals("/opt/kast/bin/kast", config.cli.binaryPath.value)

}
