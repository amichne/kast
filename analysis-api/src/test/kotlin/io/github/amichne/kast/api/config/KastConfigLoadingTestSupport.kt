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

    assertEquals(75, config.server.maxResults.value)
    assertEquals(45_000L, config.server.requestTimeoutMillis.value)
    assertEquals(
        KastConfig.defaults().server.maxConcurrentRequests.value,
        config.server.maxConcurrentRequests.value
    )
    assertEquals(false, config.cache.enabled.value)
    assertEquals(true, config.indexing.remote.enabled.value)
    assertEquals("file:///tmp/kast/source-index.db", config.indexing.remote.sourceIndexUrl.value.orNull)
    assertEquals(true, config.telemetry.enabled.value)
    assertEquals("references,rename", config.telemetry.scopes.value)
    assertEquals(config.server.maxResults.value, config.toServerLimits().maxResults)
    assertEquals(config.server.requestTimeoutMillis.value, config.toServerLimits().requestTimeoutMillis)

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
                "criticalPaths": ["src/main/**", "build.gradle.kts"],
                "ignoredPaths": ["samples/**"],
                "graph": {
                  "batchSize": 17
                },
                "relationships": {
                  "enabled": false,
                  "batchSize": 11,
                  "parallelism": 2,
                  "modulePriorityDepth": 1
                },
                "identifierIndexWaitMillis": 9876,
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
                  "lib/backends/indexer/indexer-v0.8.0",
                  "lib/backends/indexer/current"
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
    assertEquals(false, config.indexing.relationships.enabled.value)
    assertEquals(11, config.indexing.relationships.batchSize.value)
    assertEquals(2, config.indexing.relationships.parallelism.value)
    assertEquals(1, config.indexing.relationships.modulePriorityDepth.value)
    assertEquals(
        listOf("src/main/**", "build.gradle.kts"),
        config.indexing.criticalPaths.value.map(WorkspaceIndexingPattern::toString),
    )
    assertEquals(
        listOf("samples/**"),
        config.indexing.ignoredPaths.value.map(WorkspaceIndexingPattern::toString),
    )
    assertEquals(17, config.indexing.graph.batchSize.value)
    assertEquals(9876L, config.indexing.identifierIndexWaitMillis.value)
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
    assertEquals("/opt/kast/cache", config.paths.cacheDir.value)
    assertEquals("/opt/kast/runtime", config.paths.runtimeDir.value)
    assertEquals("/opt/kast/bin/kast", config.cli.binaryPath.value)

}
