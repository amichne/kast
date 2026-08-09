package io.github.amichne.kast.indexer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.ProfilingConfig
import io.github.amichne.kast.api.client.RuntimeInstanceId
import io.github.amichne.kast.api.client.ServerLaunchOptions
import io.github.amichne.kast.api.client.fields.CacheEnabled
import io.github.amichne.kast.api.client.fields.CliBinaryPath
import io.github.amichne.kast.api.client.fields.PathsBinDir
import io.github.amichne.kast.api.client.fields.PathsCacheDir
import io.github.amichne.kast.api.client.fields.PathsDescriptorDir
import io.github.amichne.kast.api.client.fields.PathsInstallRoot
import io.github.amichne.kast.api.client.fields.PathsLibDir
import io.github.amichne.kast.api.client.fields.PathsLogsDir
import io.github.amichne.kast.api.client.fields.PathsRuntimeDir
import io.github.amichne.kast.api.client.fields.PathsSocketDir
import io.github.amichne.kast.api.client.fields.ProfilingDurationSeconds
import io.github.amichne.kast.api.client.fields.ProfilingEmitManifest
import io.github.amichne.kast.api.client.fields.ProfilingEnabled
import io.github.amichne.kast.api.client.fields.ProfilingModes
import io.github.amichne.kast.api.client.fields.ProfilingOutputDir
import io.github.amichne.kast.api.contract.AnalysisTransport
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

@TestApplication
class KastRuntimeProfilingTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `enabled runtime emits readable identity-bound artifacts`() = withHermeticUserHome {
        val workspace = gitWorkspace(tempDir.resolve("enabled-workspace"))
        val profileRoot = tempDir.resolve("enabled-profiles")
        val runtimeInstanceId = RuntimeInstanceId.parse("550e8400-e29b-41d4-a716-446655440000")
        val options = options(
            workspace = workspace.root,
            runtimeInstanceId = runtimeInstanceId,
            profiling = profiling(enabled = true, modes = "cpu,alloc,lock,wall", output = profileRoot),
        )

        val runtimeVersion = withProjectClosure(workspace.root) {
            KastIndexerRuntime.start(options).use { runtime ->
                runtime.indexerRuntime.server.descriptor?.backendVersion?.value
                    ?: error("Launched runtime did not publish a descriptor")
            }
        }

        val manifestPath = onlyManifest(profileRoot)
        val manifest = Json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        assertEquals(runtimeVersion, manifest.getValue("runtimeImplementationVersion").jsonPrimitive.content)
        assertEquals(runtimeInstanceId.value, manifest.getValue("runtimeInstanceId").jsonPrimitive.content)
        assertEquals(workspace.root.toRealPath().toString(), manifest.getValue("workspaceRoot").jsonPrimitive.content)
        assertEquals(workspace.head, manifest.getValue("sourceHead").jsonPrimitive.content)
        Instant.parse(manifest.getValue("startedAt").jsonPrimitive.content)
        assertEquals(1L, manifest.getValue("durationSeconds").jsonPrimitive.content.toLong())
        assertEquals(
            setOf("cpu", "allocation", "lock", "wall"),
            manifest.getValue("modes").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )

        val artifacts = manifest.getValue("artifacts").jsonObject
            .mapValues { (_, encodedPath) -> Path.of(encodedPath.jsonPrimitive.content) }
        assertEquals(setOf("cpu", "allocation", "lock", "wall"), artifacts.keys)
        artifacts.values.forEach { artifact ->
            assertTrue(artifact.isRegularFile(), "Missing profile artifact: $artifact")
            assertTrue(Files.size(artifact) > 0L, "Empty profile artifact: $artifact")
            RecordingFile(artifact).use { recording ->
                while (recording.hasMoreEvents()) recording.readEvent()
            }
        }
        retainProfilingEvidence(manifestPath, artifacts)
    }

    @Test
    fun `disabled runtime emits no profiling output`() = withHermeticUserHome {
        val workspace = gitWorkspace(tempDir.resolve("disabled-workspace"))
        val profileRoot = tempDir.resolve("disabled-profiles")
        val options = options(
            workspace = workspace.root,
            runtimeInstanceId = RuntimeInstanceId.parse("550e8400-e29b-41d4-a716-446655440001"),
            profiling = profiling(enabled = false, modes = "cpu", output = profileRoot),
        )

        withProjectClosure(workspace.root) {
            KastIndexerRuntime.start(options).use { }
        }

        assertFalse(profileRoot.exists(), "Disabled profiling created output at $profileRoot")
    }

    @Test
    fun `unsupported mode fails before runtime admission`() = withHermeticUserHome {
        val workspace = gitWorkspace(tempDir.resolve("unsupported-workspace"))
        val profileRoot = tempDir.resolve("unsupported-profiles")
        val options = options(
            workspace = workspace.root,
            runtimeInstanceId = RuntimeInstanceId.parse("550e8400-e29b-41d4-a716-446655440002"),
            profiling = profiling(enabled = true, modes = "cpu,heap", output = profileRoot),
        )

        val result = runCatching {
            withProjectClosure(workspace.root) {
                KastIndexerRuntime.start(options).use { }
            }
        }

        assertTrue(result.isFailure, "Unsupported profiling mode was silently admitted")
        assertFalse(profileRoot.exists(), "Rejected profiling created output at $profileRoot")
    }

    private fun options(
        workspace: Path,
        runtimeInstanceId: RuntimeInstanceId,
        profiling: ProfilingConfig,
    ): IndexerServerOptions {
        val root = tempDir.resolve("runtime-${runtimeInstanceId.value}")
        val socketRoot = Path.of(requireNotNull(System.getenv("TMPDIR")))
            .toAbsolutePath()
            .normalize()
            .createDirectories()
        val config = hermeticConfig(root).copy(profiling = profiling)
        return IndexerServerOptions(
            serverOptions = ServerLaunchOptions(
                workspaceRoot = workspace,
                sourceRoots = emptyList(),
                classpathRoots = emptyList(),
                moduleName = "sources",
                transport = AnalysisTransport.UnixDomainSocket(
                    socketRoot.resolve("k573-${runtimeInstanceId.value.takeLast(4)}.sock"),
                ),
                runtimeInstanceId = runtimeInstanceId,
                requestTimeoutMillis = config.server.requestTimeoutMillis.value,
                maxResults = config.server.maxResults.value,
                maxConcurrentRequests = config.server.maxConcurrentRequests.value,
            ),
            runtimeConfig = config,
        )
    }

    private fun hermeticConfig(root: Path): KastConfig {
        val install = root.resolve("install")
        val runtime = root.resolve("runtime")
        val defaults = KastConfig.defaults()
        return defaults.copy(
            cache = defaults.cache.copy(enabled = CacheEnabled(false)),
            paths = defaults.paths.copy(
                installRoot = PathsInstallRoot(install.toString()),
                binDir = PathsBinDir(install.resolve("bin").toString()),
                libDir = PathsLibDir(install.resolve("lib").toString()),
                cacheDir = PathsCacheDir(root.resolve("cache").toString()),
                logsDir = PathsLogsDir(root.resolve("logs").toString()),
                runtimeDir = PathsRuntimeDir(runtime.toString()),
                descriptorDir = PathsDescriptorDir(runtime.resolve("descriptors").toString()),
                socketDir = PathsSocketDir(runtime.toString()),
            ),
            cli = defaults.cli.copy(binaryPath = CliBinaryPath(install.resolve("bin/kast").toString())),
        )
    }

    private fun profiling(enabled: Boolean, modes: String, output: Path): ProfilingConfig =
        KastConfig.defaults().profiling.copy(
            enabled = ProfilingEnabled(enabled),
            modes = ProfilingModes(modes),
            durationSeconds = ProfilingDurationSeconds(1L),
            outputDir = ProfilingOutputDir(output.toString()),
            emitManifest = ProfilingEmitManifest(true),
        )

    private fun gitWorkspace(root: Path): GitFixture {
        root.createDirectories()
        root.resolve("README.md").writeText("profiling fixture\n")
        git(root, "init", "--quiet")
        git(root, "add", "README.md")
        git(root, "-c", "user.name=Kast Test", "-c", "user.email=kast@example.invalid", "commit", "--quiet", "-m", "fixture")
        return GitFixture(root.toRealPath(), git(root, "rev-parse", "HEAD").trim())
    }

    private fun git(root: Path, vararg arguments: String): String {
        val processBuilder = ProcessBuilder(listOf("git") + arguments)
            .directory(root.toFile())
            .redirectErrorStream(true)
        processBuilder.environment().apply {
            put("HOME", tempDir.resolve("home").createDirectories().toString())
            put("XDG_CONFIG_HOME", tempDir.resolve("config").createDirectories().toString())
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_OPTIONAL_LOCKS", "0")
            keys.removeAll(
                setOf(
                    "GIT_DIR",
                    "GIT_WORK_TREE",
                    "GIT_COMMON_DIR",
                    "GIT_INDEX_FILE",
                    "GIT_OBJECT_DIRECTORY",
                    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
                ),
            )
        }
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output
    }

    private fun onlyManifest(profileRoot: Path): Path = Files.walk(profileRoot).use { paths ->
        paths.filter { it.fileName.toString() == "manifest.json" }.toList().single()
    }

    private fun retainProfilingEvidence(manifestPath: Path, artifacts: Map<String, Path>) {
        val gradleHome = System.getenv("GRADLE_USER_HOME")
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?.takeIf { it.fileName.toString() == "gradle-home" }
            ?: return
        val evidenceRoot = gradleHome.parent.resolve("artifacts").createDirectories()
        val retained = Files.createTempDirectory(evidenceRoot, "delivery-regression-")
        Files.copy(manifestPath, retained.resolve("manifest.json"))
        artifacts.forEach { (mode, artifact) ->
            Files.copy(artifact, retained.resolve("$mode.jfr"))
        }
        println("Retained runtime profiling evidence at $retained")
    }

    private fun <T> withProjectClosure(workspaceRoot: Path, block: () -> T): T = try {
        block()
    } finally {
        ApplicationManager.getApplication().invokeAndWait {
            WriteIntentReadAction.run {
                ProjectManagerEx.getInstanceEx().openProjects
                    .filter { project -> project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize() == workspaceRoot }
                    .forEach { project -> ProjectManagerEx.getInstanceEx().forceCloseProject(project) }
            }
        }
    }

    private fun <T> withHermeticUserHome(block: () -> T): T {
        val previous = System.getProperty("user.home")
        val userHome = tempDir.resolve("home").createDirectories().toString()
        return try {
            System.setProperty("user.home", userHome)
            block()
        } finally {
            System.setProperty("user.home", previous)
        }
    }

    private data class GitFixture(
        val root: Path,
        val head: String,
    )

    companion object {
        private val jfrDaemonWhitelist = Disposer.newDisposable("KastRuntimeProfilingTest JFR daemons")

        @BeforeAll
        @JvmStatic
        fun allowJfrDaemonThreads() {
            ThreadLeakTracker.longRunningThreadCreated(
                jfrDaemonWhitelist,
                "JFR Recording Scheduler",
                "JFR Recorder Thread",
                "JFR Periodic Tasks",
            )
        }

        @AfterAll
        @JvmStatic
        fun removeJfrDaemonThreads() {
            Disposer.dispose(jfrDaemonWhitelist)
        }
    }
}
