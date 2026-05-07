package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
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
    fun serverMaxResultsExposesSectionKeyAndTypedDefault() {
        val maxResults = KastConfig.defaults().server.maxResults

        assertEquals("server", maxResults.section)
        assertEquals("maxResults", maxResults.key)
        assertEquals(ConfigurationDefault(500), maxResults.default)
        assertEquals(500, maxResults.value)
    }

    @Test
    fun `defaults expose paths and cli sections`() {
        val configFields = KastConfig::class.java.declaredFields.map { it.name }.toSet()

        assertTrue("paths" in configFields)
        assertTrue("cli" in configFields)
    }

    @Test
    fun `defaults expose path and cli field defaults`() {
        val config = KastConfig.defaults()
        val installRoot = Path.of(System.getProperty("user.home")).resolve(".kast")
        val binDir = installRoot.resolve("bin")
        val libDir = installRoot.resolve("lib")
        val cacheDir = installRoot.resolve("cache")
        val logsDir = installRoot.resolve("logs")
        val descriptorDir = cacheDir.resolve("daemons")
        val socketDir = System.getProperty("java.io.tmpdir")
        val binaryPath = binDir.resolve("kast")
        val runtimeLibsDir = libDir.resolve("backends/current/runtime-libs")

        assertEquals("paths", config.paths.installRoot.section)
        assertEquals("installRoot", config.paths.installRoot.key)
        assertEquals(ConfigurationDefault(installRoot.toString()), config.paths.installRoot.default)
        assertEquals(installRoot.toString(), config.paths.installRoot.value)
        assertEquals(binDir.toString(), config.paths.binDir.value)
        assertEquals(libDir.toString(), config.paths.libDir.value)
        assertEquals(cacheDir.toString(), config.paths.cacheDir.value)
        assertEquals(logsDir.toString(), config.paths.logsDir.value)
        assertEquals(descriptorDir.toString(), config.paths.descriptorDir.value)
        assertEquals(socketDir, config.paths.socketDir.value)

        assertEquals("cli", config.cli.binaryPath.section)
        assertEquals("binaryPath", config.cli.binaryPath.key)
        assertEquals(binaryPath.toString(), config.cli.binaryPath.value)
        assertEquals(runtimeLibsDir.toString(), config.backends.standalone.runtimeLibsDir.value.orNull)
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
            "indexing" to "identifierIndexWaitMillis",
            "indexing" to "referenceBatchSize",
            "indexing.remote" to "enabled",
            "indexing.remote" to "sourceIndexUrl",
            "cache" to "enabled",
            "cache" to "writeDelayMillis",
            "cache" to "sourceIndexSaveDelayMillis",
            "watcher" to "debounceMillis",
            "gradle" to "toolingApiTimeoutMillis",
            "gradle" to "maxIncludedProjects",
            "telemetry" to "enabled",
            "telemetry" to "scopes",
            "telemetry" to "detail",
            "telemetry" to "outputFile",
            "backends.standalone" to "enabled",
            "backends.standalone" to "runtimeLibsDir",
            "backends.intellij" to "enabled",
            "paths" to "installRoot",
            "paths" to "binDir",
            "paths" to "libDir",
            "paths" to "cacheDir",
            "paths" to "logsDir",
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
    fun `workspace directory resolver uses git remote hierarchy when origin is parseable`() {
        val configHome = tempDir.resolve("config-home")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            configHome = { configHome },
            gitRemoteResolver = { GitRemote(host = "github.com", owner = "amichne", repo = "kast") },
        )

        val dataDirectory = resolver.workspaceDataDirectory(workspaceRoot)

        assertEquals(
            configHome.resolve("workspaces/github.com/amichne/kast"),
            dataDirectory,
        )
        assertEquals(dataDirectory.resolve("cache"), resolver.workspaceCacheDirectory(workspaceRoot))
        assertEquals(dataDirectory.resolve("cache/source-index.db"), resolver.workspaceDatabasePath(workspaceRoot))
    }

    @Test
    fun `workspace directory resolver persists local workspace ids when origin is unavailable`() {
        val configHome = tempDir.resolve("config-home")
        val workspaceRoot = Path.of("/workspace/not-git")
        val resolver = WorkspaceDirectoryResolver(
            configHome = { configHome },
            gitRemoteResolver = { null },
        )

        val first = resolver.workspaceDataDirectory(workspaceRoot)
        val second = resolver.workspaceDataDirectory(workspaceRoot)

        assertEquals(first, second)
        assertTrue(first.startsWith(configHome.resolve("workspaces/local")))
        assertTrue(
            configHome.resolve("local-workspaces.json")
                .readText()
                .contains(workspaceRoot.toAbsolutePath().normalize().toString())
        )
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

        assertEquals(321, config.server.maxResults.value)
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
            assertEquals(321, config.server.maxResults.value)
        }

        assertTrue(stdout.isBlank(), "Expected KastConfig.load to keep stdout clean, but got: $stdout")
    }

    @Test
    @OptIn(ExperimentalHoplite::class)
    fun hopliteDecodesReadableTomlDirectlyIntoConfigurationFieldOverrideLeaves() {
        val installRoot = tempDir.resolve("install-root")
        val sourceIndexUrl = "file:///private/var/kast/source-index.db"
        val configFile = tempDir.resolve("field-overrides.toml").apply {
            writeText(
                """
                [server]
                maxResults = 123

                [paths]
                installRoot = "$installRoot"

                [indexing.remote]
                sourceIndexUrl = "$sourceIndexUrl"
                """.trimIndent(),
            )
        }

        val loaded = ConfigLoaderBuilder.empty()
            .withClassLoader(KastConfig::class.java.classLoader)
            .addDefaultDecoders()
            .addDefaultPreprocessors()
            .addDefaultNodeTransformers()
            .addDefaultParamMappers()
            .addDefaultParsers()
            .withExplicitSealedTypes()
            .allowEmptyConfigFiles()
            .build()
            .loadConfigOrThrow<KastConfigOverride>(listOf(configFile.toString()))

        val maxResults: Any? = loaded.server?.maxResults
        val decodedInstallRoot: Any? = loaded.paths?.installRoot
        val decodedSourceIndexUrl: Any? = loaded.indexing?.remote?.sourceIndexUrl

        assertTrue(
            maxResults is ServerMaxResults,
            "Expected server.maxResults to decode into ServerMaxResults, got $maxResults"
        )
        assertEquals(ServerMaxResults(123), maxResults)
        assertEquals("server", (maxResults as ServerMaxResults).section)
        assertEquals("maxResults", maxResults.key)
        assertEquals(500, maxResults.default.unwrap)

        assertTrue(
            decodedInstallRoot is PathsInstallRoot,
            "Expected paths.installRoot to decode into PathsInstallRoot, got $decodedInstallRoot"
        )
        assertEquals(PathsInstallRoot(installRoot.toString()), decodedInstallRoot)

        assertTrue(
            decodedSourceIndexUrl is IndexingRemoteSourceIndexUrl,
            "Expected indexing.remote.sourceIndexUrl to decode into IndexingRemoteSourceIndexUrl, got $decodedSourceIndexUrl",
        )
        assertEquals(IndexingRemoteSourceIndexUrl(OptionalConfigString(sourceIndexUrl)), decodedSourceIndexUrl)
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
