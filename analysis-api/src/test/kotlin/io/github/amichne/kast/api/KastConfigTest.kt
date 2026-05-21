package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.reflect.full.createType
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
    fun `configuration field decoder supports every field wrapper`() {
        val decoder = ConfigurationFieldDecoder()
        val missingTypes = ConfigurationField::class.sealedSubclasses
            .filterNot { subclass -> decoder.supports(subclass.createType()) }
            .mapNotNull { subclass -> subclass.qualifiedName }

        assertTrue(
            missingTypes.isEmpty(),
            "Add ConfigurationFieldDecoder support for:\n" + missingTypes.joinToString("\n"),
        )
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
            "profiling" to "enabled",
            "profiling" to "modes",
            "profiling" to "durationSeconds",
            "profiling" to "outputDir",
            "profiling" to "otlpEndpoint",
            "profiling" to "emitManifest",
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
    fun `config loader decodes every advertised configuration field`() {
        val configHome = tempDir.resolve("config-home")
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = tempDir.resolve("workspace")
        val expectedValues = ConfigurationField.defaultFields().associate { field ->
            field.section to field.key to overrideValueFor(field)
        }
        configHome.resolve("config.toml").apply {
            parent.toFile().mkdirs()
            writeText(renderToml(expectedValues))
        }

        val config = KastConfig.load(
            workspaceRoot = workspaceRoot,
            configHome = { configHome },
            workspaceDirectoryResolver = WorkspaceDirectoryResolver(
                configHome = { configHome },
                installRoot = { installRoot },
                gitRemoteResolver = { null },
            ),
        )
        val actualValues = configurationFields(config).associate { field ->
            field.section to field.key to field.value
        }

        assertEquals(expectedValues.keys, actualValues.keys)
        assertEquals(expectedValues, actualValues)
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
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            configHome = { configHome },
            installRoot = { installRoot },
            gitRemoteResolver = { GitRemote(host = "github.com", owner = "amichne", repo = "kast") },
        )

        val dataDirectory = resolver.workspaceDataDirectory(workspaceRoot)

        assertEquals(
            installRoot.resolve("workspaces/github.com/amichne/kast"),
            dataDirectory,
        )
        assertEquals(dataDirectory.resolve("cache"), resolver.workspaceCacheDirectory(workspaceRoot))
        assertEquals(dataDirectory.resolve("cache/source-index.db"), resolver.workspaceDatabasePath(workspaceRoot))
    }

    @Test
    fun `workspace directory resolver persists local workspace ids when origin is unavailable`() {
        val configHome = tempDir.resolve("config-home")
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = Path.of("/workspace/not-git")
        val resolver = WorkspaceDirectoryResolver(
            configHome = { configHome },
            installRoot = { installRoot },
            gitRemoteResolver = { null },
        )

        val first = resolver.workspaceDataDirectory(workspaceRoot)
        val second = resolver.workspaceDataDirectory(workspaceRoot)

        assertEquals(first, second)
        assertTrue(first.startsWith(installRoot.resolve("workspaces/local")))
        assertTrue(
            installRoot.resolve("workspaces/local-workspaces.json")
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
            configHome = { configHome },
            installRoot = { installRoot },
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
    fun `config loader does not depend on the thread context classloader`() {
        val configHome = tempDir.resolve("config-home")
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            configHome = { configHome },
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
    fun `config loader does not write Hoplite warnings to stdout`() {
        val configHome = tempDir.resolve("config-home")
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            configHome = { configHome },
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
    fun `config loader supports release installer path overrides`() {
        val configHome = tempDir.resolve("config-home")
        val workspaceRoot = tempDir.resolve("workspace")
        val installRoot = tempDir.resolve("install-root")
        val binDir = installRoot.resolve("bin")
        val binaryPath = binDir.resolve("kast")
        val runtimeLibsDir = installRoot.resolve("backends/standalone-probe/runtime-libs")
        configHome.resolve("config.toml").apply {
            parent.toFile().mkdirs()
            writeText(
                """
                [paths]
                install-root = "$installRoot"
                bin-dir = "$binDir"

                [cli]
                binary-path = "$binaryPath"

                [backends.standalone]
                runtime-libs-dir = "$runtimeLibsDir"
                """.trimIndent(),
            )
        }

        val config = KastConfig.load(
            workspaceRoot = workspaceRoot,
            configHome = { configHome },
            workspaceDirectoryResolver = WorkspaceDirectoryResolver(
                configHome = { configHome },
                installRoot = { installRoot },
                gitRemoteResolver = { null },
            ),
        )

        assertEquals(installRoot.toString(), config.paths.installRoot.value)
        assertEquals(binDir.toString(), config.paths.binDir.value)
        assertEquals(binaryPath.toString(), config.cli.binaryPath.value)
        assertEquals(runtimeLibsDir.toString(), config.backends.standalone.runtimeLibsDir.value.orNull)
    }

    @Test
    fun `config loader supports camel case config keys`() {
        val configHome = tempDir.resolve("config-home")
        val installRoot = tempDir.resolve("install-root")
        val binDir = installRoot.resolve("custom-bin")
        val binaryPath = binDir.resolve("custom-kast")
        val runtimeLibsDir = installRoot.resolve("custom-runtime-libs")
        val workspaceRoot = tempDir.resolve("workspace")
        val sourceIndexUrl = "file:///private/var/kast/source-index.db"
        configHome.resolve("config.toml").apply {
            parent.toFile().mkdirs()
            writeText(
                """
                [server]
                maxResults = 123

                [paths]
                installRoot = "$installRoot"
                binDir = "$binDir"

                [indexing.remote]
                sourceIndexUrl = "$sourceIndexUrl"

                [cli]
                binaryPath = "$binaryPath"

                [backends.standalone]
                runtimeLibsDir = "$runtimeLibsDir"
                """.trimIndent(),
            )
        }

        val config = KastConfig.load(
            workspaceRoot = workspaceRoot,
            configHome = { configHome },
            workspaceDirectoryResolver = WorkspaceDirectoryResolver(
                configHome = { configHome },
                installRoot = { installRoot },
                gitRemoteResolver = { null },
            ),
        )

        assertEquals(123, config.server.maxResults.value)
        assertEquals(installRoot.toString(), config.paths.installRoot.value)
        assertEquals(binDir.toString(), config.paths.binDir.value)
        assertEquals(binaryPath.toString(), config.cli.binaryPath.value)
        assertEquals(runtimeLibsDir.toString(), config.backends.standalone.runtimeLibsDir.value.orNull)
        assertEquals(sourceIndexUrl, config.indexing.remote.sourceIndexUrl.value.orNull)
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

    private fun overrideValueFor(field: ConfigurationField<*>): Any = when (val value = field.value) {
        is Boolean -> !value
        is Int -> value + 1
        is Long -> value + 1L
        is OptionalConfigString -> OptionalConfigString("override-${field.section.replace(".", "-")}-${field.key}")
        is String -> "override-${field.section.replace(".", "-")}-${field.key}"
        else -> error("Unsupported configuration field value for ${field.section}.${field.key}: $value")
    }

    private fun renderToml(values: Map<Pair<String, String>, Any>): String = values.entries
        .groupBy { it.key.first }
        .entries
        .joinToString("\n\n") { (section, entries) ->
            buildString {
                appendLine("[$section]")
                entries.forEach { (key, value) ->
                    appendLine("${key.second.toKebabCase()} = ${value.toTomlLiteral()}")
                }
            }.trimEnd()
        }

    private fun Any.toTomlLiteral(): String = when (this) {
        is Boolean -> toString()
        is Int -> toString()
        is Long -> toString()
        is OptionalConfigString -> checkNotNull(orNull).toTomlStringLiteral()
        is String -> toTomlStringLiteral()
        else -> error("Unsupported TOML value: $this")
    }

    private fun String.toTomlStringLiteral(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun String.toKebabCase(): String = fold("") { acc, char ->
        when {
            char.isUpperCase() && acc.isEmpty() -> char.lowercaseChar().toString()
            char.isUpperCase() -> acc + "-" + char.lowercaseChar()
            else -> acc + char
        }
    }

    private fun configurationFields(value: Any): List<ConfigurationField<*>> {
        if (value is ConfigurationField<*>) return listOf(value)
        if (value.javaClass.`package`?.name != KastConfig::class.java.`package`.name) return emptyList()
        return value.javaClass.declaredFields.flatMap { field ->
            if (Modifier.isStatic(field.modifiers)) return@flatMap emptyList()
            field.isAccessible = true
            val fieldValue = field.get(value) ?: return@flatMap emptyList()
            configurationFields(fieldValue)
        }
    }
}
