package io.github.amichne.kast.headless

import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.contract.AnalysisTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class HeadlessServerOptionsTest {
    @TempDir
    lateinit var tempDir: Path

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

        val options = HeadlessServerOptions.parseStarterArgs(
            listOf(
                HeadlessApplicationStarter.COMMAND_NAME,
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
    fun `starter args apply launch profiling override to resolved runtime config`() {
        val runtimeConfig = tempDir.resolve("runtime-config.json").apply {
            writeText("{}")
        }

        val options = HeadlessServerOptions.parseStarterArgs(
            listOf(
                HeadlessApplicationStarter.COMMAND_NAME,
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

    @Test
    @Suppress("DEPRECATION")
    fun `plain project open task skips external model import work before server registration`() {
        val task = HeadlessProjectOpener.openProjectTask()

        assertEquals(false, task.isRefreshVfsNeeded)
        assertEquals(false, task.runConfigurators)
        assertEquals(false, task.runConversionBeforeOpen)
        assertEquals(false, task.preloadServices)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `Gradle project open task defers external model import to bootstrap`() {
        val task = HeadlessProjectOpener.openProjectTask()

        assertEquals(false, task.isRefreshVfsNeeded)
        assertEquals(false, task.runConfigurators)
        assertEquals(false, task.runConversionBeforeOpen)
        assertEquals(false, task.preloadServices)
    }

    @Test
    fun `workspace kind detects Gradle marker files`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)
        workspace.resolve("settings.gradle.kts").writeText("")

        assertEquals(HeadlessWorkspaceKind.GRADLE, HeadlessWorkspaceKind.detect(workspace))
    }

    @Test
    fun `Gradle bootstrap links checkout when IDEA model starts without modules`() {
        val workspace = tempDir.resolve("workspace")
        val observedPaths = mutableListOf<String>()
        var waitCount = 0
        val modelSnapshots = ArrayDeque(
            listOf(
                modelReadiness(),
                modelReadiness(),
                modelReadiness(moduleNames = listOf(":app")),
            ),
        )
        val bootstrap = HeadlessGradleProjectBootstrap(
            waitForProjectModel = {
                waitCount += 1
            },
            inspectProjectModel = {
                modelSnapshots.removeFirst()
            },
            canLinkGradleProject = { _, _ -> true },
            linkAndImportGradleProject = { _, externalProjectPath ->
                observedPaths += externalProjectPath
            },
        )

        val result = bootstrap.bootstrap(projectStub(), workspace, HeadlessWorkspaceKind.GRADLE)

        assertEquals(
            HeadlessProjectModelBootstrapResult.Ready(moduleNames = listOf(":app"), linkedGradleProject = true),
            result,
        )
        assertEquals(listOf(workspace.toAbsolutePath().normalize().toString()), observedPaths)
        assertEquals(2, waitCount)
    }

    @Test
    fun `Gradle bootstrap adopts an automatic startup sync without scheduling another import`() {
        val workspace = tempDir.resolve("workspace")
        var waitCount = 0
        var explicitImportCount = 0
        val modelSnapshots = ArrayDeque(
            listOf(
                modelReadiness(),
                modelReadiness(moduleNames = listOf(":app")),
            ),
        )
        val bootstrap = HeadlessGradleProjectBootstrap(
            waitForProjectModel = { waitCount += 1 },
            inspectProjectModel = { modelSnapshots.removeFirst() },
            canLinkGradleProject = { _, _ -> true },
            linkAndImportGradleProject = { _, _ -> explicitImportCount += 1 },
        )

        val result = bootstrap.bootstrap(projectStub(), workspace, HeadlessWorkspaceKind.GRADLE)

        assertEquals(
            HeadlessProjectModelBootstrapResult.Ready(moduleNames = listOf(":app"), linkedGradleProject = true),
            result,
        )
        assertEquals(1, waitCount)
        assertEquals(0, explicitImportCount)
    }

    @Test
    fun `Gradle bootstrap refreshes a persisted module model before declaring readiness`() {
        val workspace = tempDir.resolve("workspace")
        val observedPaths = mutableListOf<String>()
        var waitCount = 0
        val modelSnapshots = ArrayDeque(
            listOf(
                modelReadiness(
                    moduleNames = listOf(":stale"),
                    compilerReadyKotlinModuleNames = emptyList(),
                ),
                modelReadiness(
                    moduleNames = listOf(":stale"),
                    compilerReadyKotlinModuleNames = emptyList(),
                ),
                modelReadiness(moduleNames = listOf(":fresh")),
            ),
        )
        val bootstrap = HeadlessGradleProjectBootstrap(
            waitForProjectModel = {
                waitCount += 1
            },
            inspectProjectModel = {
                modelSnapshots.removeFirst()
            },
            canLinkGradleProject = { _, _ -> true },
            linkAndImportGradleProject = { _, externalProjectPath ->
                observedPaths += externalProjectPath
            },
        )

        val result = bootstrap.bootstrap(projectStub(), workspace, HeadlessWorkspaceKind.GRADLE)

        assertEquals(
            HeadlessProjectModelBootstrapResult.Ready(moduleNames = listOf(":fresh"), linkedGradleProject = true),
            result,
        )
        assertEquals(listOf(workspace.toAbsolutePath().normalize().toString()), observedPaths)
        assertEquals(2, waitCount)
    }

    @Test
    fun `existing Gradle link is recognized without registering the checkout twice`() {
        val workspace = tempDir.resolve("workspace").toAbsolutePath().normalize()
        val linkedProject = GradleProjectSettings().apply {
            externalProjectPath = workspace.resolve(".").toString()
        }

        assertTrue(
            HeadlessGradleProjectImportBridge.hasLinkedProject(
                listOf(linkedProject),
                workspace.toString(),
            ),
        )
        assertEquals(
            false,
            HeadlessGradleProjectImportBridge.hasLinkedProject(
                listOf(linkedProject),
                workspace.resolveSibling("other").toString(),
            ),
        )
    }

    @Test
    fun `concurrent Gradle sync failure is recognized as existing work`() {
        assertTrue(
            HeadlessGradleProjectImportBridge.isConcurrentGradleSyncFailure(
                RuntimeException("Another 'Sync project' task is currently running for the project: /workspace"),
            ),
        )
    }

    @Test
    fun `Java-only source modules do not weaken Kotlin compiler readiness`() {
        val readiness = modelReadiness(
            moduleNames = listOf(":app", ":java-support"),
            kotlinSourceModuleNames = listOf(":app"),
            compilerReadyKotlinModuleNames = listOf(":app"),
        )

        assertTrue(readiness.compilerReady)
        assertEquals(emptyList<String>(), readiness.unavailableKotlinModuleNames)
    }

    @Test
    fun `Gradle bootstrap waits through a recovered but temporarily unusable compiler model`() {
        val workspace = tempDir.resolve("workspace")
        var waitCount = 0
        var retryCount = 0
        val modelSnapshots = ArrayDeque(
            listOf(
                modelReadiness(
                    moduleNames = listOf(":app"),
                    compilerReadyKotlinModuleNames = emptyList(),
                ),
                modelReadiness(
                    moduleNames = listOf(":app"),
                    compilerReadyKotlinModuleNames = emptyList(),
                ),
                modelReadiness(
                    moduleNames = listOf(":app"),
                    compilerReadyKotlinModuleNames = emptyList(),
                ),
                modelReadiness(moduleNames = listOf(":app")),
            ),
        )
        val bootstrap = HeadlessGradleProjectBootstrap(
            waitForProjectModel = { waitCount += 1 },
            inspectProjectModel = { modelSnapshots.removeFirst() },
            canLinkGradleProject = { _, _ -> true },
            linkAndImportGradleProject = { _, _ -> },
            waitBeforeReadinessRetry = { retryCount += 1 },
            maxReadinessChecks = 2,
        )

        val result = bootstrap.bootstrap(projectStub(), workspace, HeadlessWorkspaceKind.GRADLE)

        assertEquals(
            HeadlessProjectModelBootstrapResult.Ready(moduleNames = listOf(":app"), linkedGradleProject = true),
            result,
        )
        assertEquals(3, waitCount)
        assertEquals(1, retryCount)
    }

    @Test
    fun `Gradle bootstrap fails when sync still reports no modules`() {
        val workspace = tempDir.resolve("workspace")
        val bootstrap = HeadlessGradleProjectBootstrap(
            waitForProjectModel = {},
            inspectProjectModel = { modelReadiness() },
            canLinkGradleProject = { _, _ -> true },
            linkAndImportGradleProject = { _, _ -> },
            waitBeforeReadinessRetry = {},
            maxReadinessChecks = 1,
        )

        assertThrows(HeadlessGradleModelUnavailableException::class.java) {
            bootstrap.bootstrap(projectStub(), workspace, HeadlessWorkspaceKind.GRADLE)
        }
    }

    private fun modelReadiness(
        moduleNames: List<String> = emptyList(),
        kotlinSourceModuleNames: List<String> = moduleNames,
        compilerReadyKotlinModuleNames: List<String> = kotlinSourceModuleNames,
    ): HeadlessGradleModelReadiness = HeadlessGradleModelReadiness(
        moduleNames = moduleNames.sorted(),
        kotlinSourceModuleNames = kotlinSourceModuleNames.sorted(),
        compilerReadyKotlinModuleNames = compilerReadyKotlinModuleNames.sorted(),
    )

    private fun projectStub(): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "stub"
                "isDisposed" -> false
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "ProjectStub"
                else -> null
            }
        } as Project

}
