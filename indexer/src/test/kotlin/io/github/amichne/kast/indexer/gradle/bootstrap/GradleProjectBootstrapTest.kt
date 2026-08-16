package io.github.amichne.kast.indexer.gradle.bootstrap

import io.github.amichne.kast.indexer.gradle.settlement.GradleModelUnavailableException
import io.github.amichne.kast.indexer.project.ProjectModelBootstrapResult
import io.github.amichne.kast.indexer.project.ProjectOpener
import io.github.amichne.kast.indexer.project.WorkspaceKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer
import kotlin.io.path.writeText

class GradleProjectBootstrapTest {
    @TempDir
    lateinit var tempDir: Path

    private val gradleProjectCache: Path
        get() = tempDir.resolve("gradle-project-cache")

    @Test
    @Suppress("DEPRECATION")
    fun `plain project open task skips external model import work before server registration`() {
        val task = ProjectOpener.openProjectTask()

        assertEquals(false, task.isRefreshVfsNeeded)
        assertEquals(false, task.runConfigurators)
        assertEquals(false, task.runConversionBeforeOpen)
        assertEquals(false, task.preloadServices)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `Gradle project open task defers external model import to bootstrap`() {
        val task = ProjectOpener.openProjectTask()

        assertEquals(false, task.isRefreshVfsNeeded)
        assertEquals(false, task.runConfigurators)
        assertEquals(false, task.runConversionBeforeOpen)
        assertEquals(false, task.preloadServices)
    }

    @Test
    fun `indexer Gradle import disables dependency source downloads without discarding VM options`() {
        val disableSources = "-Didea.gradle.download.sources.force=false"

        assertEquals(
            disableSources,
            GradleProjectImportBridge.withDependencySourceDownloadsDisabled(null),
        )
        assertEquals(
            "-Xmx2g $disableSources",
            GradleProjectImportBridge.withDependencySourceDownloadsDisabled("-Xmx2g"),
        )
        assertEquals(
            "-Xmx2g $disableSources",
            GradleProjectImportBridge.withDependencySourceDownloadsDisabled("-Xmx2g $disableSources"),
        )
    }

    @Test
    fun `indexer application disables dependency source downloads before project open`() {
        val observedValues = mutableListOf<Boolean>()

        GradleProjectImportBridge.configureIndexerApplication(Consumer(observedValues::add))

        assertEquals(listOf(false), observedValues)
    }

    @Test
    fun `Gradle bootstrap configures the lean indexer import before inspecting the model`() {
        val workspace = tempDir.resolve("workspace")
        val phases = mutableListOf<String>()
        val bootstrap = GradleProjectBootstrap(
            configureGradleImport = { _, _ -> phases += "configure" },
            waitForProjectModel = { settlementEvidence() },
            inspectProjectModel = {
                phases += "inspect"
                modelReadiness(moduleNames = listOf(":app"))
            },
            canLinkGradleProject = { _, _ -> true },
            hasLinkedGradleProject = { _, _ -> true },
        )

        val result = bootstrap.bootstrap(projectStub(), workspace, WorkspaceKind.GRADLE, gradleProjectCache)

        assertEquals(
            ProjectModelBootstrapResult.Ready(moduleNames = listOf(":app"), linkedGradleProject = true),
            result,
        )
        assertEquals(listOf("configure", "inspect", "inspect"), phases)
    }

    @Test
    fun `workspace kind detects Gradle marker files`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)
        workspace.resolve("settings.gradle.kts").writeText("")

        assertEquals(WorkspaceKind.GRADLE, WorkspaceKind.detect(workspace))
    }

    @Test
    fun `Gradle bootstrap links checkout when IDEA model starts without modules`() {
        val workspace = tempDir.resolve("workspace")
        val observedPaths = mutableListOf<String>()
        var waitCount = 0
        var linked = false
        val modelSnapshots = ArrayDeque(
            listOf(
                modelReadiness(),
                modelReadiness(),
                modelReadiness(moduleNames = listOf(":app")),
            ),
        )
        val bootstrap = GradleProjectBootstrap(
            configureGradleImport = { _, _ -> },
            waitForProjectModel = {
                waitCount += 1
                settlementEvidence()
            },
            inspectProjectModel = {
                modelSnapshots.removeFirst()
            },
            canLinkGradleProject = { _, _ -> true },
            hasLinkedGradleProject = { _, _ -> linked },
            linkAndImportGradleProject = { _, externalProjectPath, _ ->
                linked = true
                observedPaths += externalProjectPath
            },
        )

        val result = bootstrap.bootstrap(projectStub(), workspace, WorkspaceKind.GRADLE, gradleProjectCache)

        assertEquals(
            ProjectModelBootstrapResult.Ready(moduleNames = listOf(":app"), linkedGradleProject = true),
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
        var linked = false
        val modelSnapshots = ArrayDeque(
            listOf(
                modelReadiness(),
                modelReadiness(moduleNames = listOf(":app")),
            ),
        )
        val bootstrap = GradleProjectBootstrap(
            configureGradleImport = { _, _ -> },
            waitForProjectModel = {
                waitCount += 1
                linked = true
                settlementEvidence()
            },
            inspectProjectModel = { modelSnapshots.removeFirst() },
            canLinkGradleProject = { _, _ -> true },
            hasLinkedGradleProject = { _, _ -> linked },
            linkAndImportGradleProject = { _, _, _ -> explicitImportCount += 1 },
        )

        val result = bootstrap.bootstrap(projectStub(), workspace, WorkspaceKind.GRADLE, gradleProjectCache)

        assertEquals(
            ProjectModelBootstrapResult.Ready(moduleNames = listOf(":app"), linkedGradleProject = true),
            result,
        )
        assertEquals(1, waitCount)
        assertEquals(0, explicitImportCount)
    }

    @Test
    fun `Gradle bootstrap does not admit an unlinked cached compiler model`() {
        val workspace = tempDir.resolve("workspace")
        val observedPaths = mutableListOf<String>()
        var waitCount = 0
        var linked = false
        val modelSnapshots = ArrayDeque(
            listOf(
                modelReadiness(moduleNames = listOf(":stale")),
                modelReadiness(moduleNames = listOf(":stale")),
                modelReadiness(moduleNames = listOf(":fresh")),
            ),
        )
        val bootstrap = GradleProjectBootstrap(
            configureGradleImport = { _, _ -> },
            waitForProjectModel = {
                waitCount += 1
                settlementEvidence()
            },
            inspectProjectModel = { modelSnapshots.removeFirst() },
            canLinkGradleProject = { _, _ -> true },
            hasLinkedGradleProject = { _, _ -> linked },
            linkAndImportGradleProject = { _, externalProjectPath, _ ->
                linked = true
                observedPaths += externalProjectPath
            },
        )

        val result = bootstrap.bootstrap(projectStub(), workspace, WorkspaceKind.GRADLE, gradleProjectCache)

        assertEquals(
            ProjectModelBootstrapResult.Ready(moduleNames = listOf(":fresh"), linkedGradleProject = true),
            result,
        )
        assertEquals(listOf(workspace.toAbsolutePath().normalize().toString()), observedPaths)
        assertEquals(2, waitCount)
        assertTrue(linked)
    }

    @Test
    fun `Gradle bootstrap fails closed when concurrent reload leaves exact root unlinked`() {
        val workspace = tempDir.resolve("workspace")
        val bootstrap = GradleProjectBootstrap(
            configureGradleImport = { _, _ -> },
            waitForProjectModel = { settlementEvidence() },
            inspectProjectModel = { modelReadiness(moduleNames = listOf(":stale")) },
            canLinkGradleProject = { _, _ -> true },
            hasLinkedGradleProject = { _, _ -> false },
            linkAndImportGradleProject = { _, _, _ -> },
            waitBeforeReadinessRetry = {},
            maxReadinessChecks = 1,
        )

        val error = assertThrows(GradleModelUnavailableException::class.java) {
            bootstrap.bootstrap(projectStub(), workspace, WorkspaceKind.GRADLE, gradleProjectCache)
        }

        assertTrue(error.message.orEmpty().contains(workspace.toAbsolutePath().normalize().toString()))
    }

    @Test
    fun `Gradle bootstrap refreshes a persisted module model before declaring readiness`() {
        val workspace = tempDir.resolve("workspace")
        val observedPaths = mutableListOf<String>()
        var waitCount = 0
        var linked = false
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
        val bootstrap = GradleProjectBootstrap(
            configureGradleImport = { _, _ -> },
            waitForProjectModel = {
                waitCount += 1
                settlementEvidence()
            },
            inspectProjectModel = {
                modelSnapshots.removeFirst()
            },
            canLinkGradleProject = { _, _ -> true },
            hasLinkedGradleProject = { _, _ -> linked },
            linkAndImportGradleProject = { _, externalProjectPath, _ ->
                linked = true
                observedPaths += externalProjectPath
            },
        )

        val result = bootstrap.bootstrap(projectStub(), workspace, WorkspaceKind.GRADLE, gradleProjectCache)

        assertEquals(
            ProjectModelBootstrapResult.Ready(moduleNames = listOf(":fresh"), linkedGradleProject = true),
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
            GradleProjectImportBridge.hasLinkedProject(
                listOf(linkedProject),
                workspace.toString(),
            ),
        )
        assertEquals(
            false,
            GradleProjectImportBridge.hasLinkedProject(
                listOf(linkedProject),
                workspace.resolveSibling("other").toString(),
            ),
        )
    }

    @Test
    fun `concurrent Gradle sync failure is recognized as existing work`() {
        assertTrue(
            GradleProjectImportBridge.isConcurrentGradleSyncFailure(
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
        val bootstrap = GradleProjectBootstrap(
            configureGradleImport = { _, _ -> },
            waitForProjectModel = {
                waitCount += 1
                settlementEvidence()
            },
            inspectProjectModel = { modelSnapshots.removeFirst() },
            canLinkGradleProject = { _, _ -> true },
            hasLinkedGradleProject = { _, _ -> true },
            linkAndImportGradleProject = { _, _, _ -> },
            waitBeforeReadinessRetry = { retryCount += 1 },
            maxReadinessChecks = 2,
        )

        val result = bootstrap.bootstrap(projectStub(), workspace, WorkspaceKind.GRADLE, gradleProjectCache)

        assertEquals(
            ProjectModelBootstrapResult.Ready(moduleNames = listOf(":app"), linkedGradleProject = true),
            result,
        )
        assertEquals(3, waitCount)
        assertEquals(1, retryCount)
    }

    @Test
    fun `Gradle bootstrap fails when sync still reports no modules`() {
        val workspace = tempDir.resolve("workspace")
        val bootstrap = GradleProjectBootstrap(
            configureGradleImport = { _, _ -> },
            waitForProjectModel = { settlementEvidence() },
            inspectProjectModel = { modelReadiness() },
            canLinkGradleProject = { _, _ -> true },
            linkAndImportGradleProject = { _, _, _ -> },
            waitBeforeReadinessRetry = {},
            maxReadinessChecks = 1,
        )

        assertThrows(GradleModelUnavailableException::class.java) {
            bootstrap.bootstrap(projectStub(), workspace, WorkspaceKind.GRADLE, gradleProjectCache)
        }
    }

}
