package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.fields.IdeaBackendEnabled
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.ProjectOpenConfig
import io.github.amichne.kast.api.client.fields.ProjectOpenAutoExcludeGit
import io.github.amichne.kast.api.client.fields.ProjectOpenGradleLoadEnabled
import io.github.amichne.kast.api.client.fields.ProjectOpenProfile
import io.github.amichne.kast.api.client.fields.ProjectOpenProfileAutoInit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class KastProjectOpenAutoIndexingTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
    }

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `project open keeps reference indexing independent from Gradle load completion`() {
        val project = projectFixture.get()
        var loadedGradleWorkspaceRoot: Path? = null
        var startedProject: Project? = null
        var gradleCompletion: ((Throwable?) -> Unit)? = null
        val events = mutableListOf<String>()
        val config = KastConfig.defaults()

        val started = KastProjectOpenAutoIndexing.execute(
            project = project,
            config = config,
            loadGradleProject = { workspaceRoot, startupConfig, onComplete ->
                assertSame(config, startupConfig)
                events.add("gradle")
                loadedGradleWorkspaceRoot = workspaceRoot
                gradleCompletion = onComplete
                ProjectOpenGradleLoadResult.Requested(GradleProjectLoadRequest.Link(workspaceRoot))
            },
            startBackend = { _, startupConfig ->
                assertSame(config, startupConfig)
                events.add("backend")
                startedProject = project
            },
            startReferenceIndex = { events.add("index") },
            restartBackend = { events.add("restart") },
        )

        assertTrue(started)
        assertSame(project, startedProject)
        assertEquals(listOf("backend", "index", "gradle"), events)
        gradleCompletion?.invoke(IllegalStateException("Another 'Sync project' task is currently running"))
        assertEquals(listOf("backend", "index", "gradle"), events)
        assertNotNull(loadedGradleWorkspaceRoot)
        assertEquals(loadedGradleWorkspaceRoot, loadedGradleWorkspaceRoot?.toAbsolutePath()?.normalize())
    }

    @Test
    fun `successful Gradle completion retries indexing through the existing backend lifecycle`() {
        val project = projectFixture.get()
        var gradleCompletion: ((Throwable?) -> Unit)? = null
        val events = mutableListOf<String>()

        val started = KastProjectOpenAutoIndexing.execute(
            project = project,
            config = KastConfig.defaults(),
            loadGradleProject = { workspaceRoot, _, onComplete ->
                events.add("gradle")
                gradleCompletion = onComplete
                ProjectOpenGradleLoadResult.Requested(GradleProjectLoadRequest.Refresh(workspaceRoot))
            },
            startBackend = { _, _ -> events.add("backend") },
            startReferenceIndex = { events.add("index") },
            restartBackend = { events.add("restart") },
        )

        assertTrue(started)
        assertEquals(listOf("backend", "index", "gradle"), events)

        gradleCompletion?.invoke(null)

        assertEquals(listOf("backend", "index", "gradle", "restart"), events)
    }

    @Test
    fun `Gradle project load request schedules background work`() {
        val project = projectFixture.get()
        Files.writeString(tempDir.resolve("settings.gradle.kts"), "pluginManagement {}\n")
        var scheduled = false

        val result = KastProjectOpenGradleLoad.execute(
            project = project,
            workspaceRoot = tempDir,
            enabled = ProjectOpenGradleLoadEnabled(true),
            scheduleGradleLoad = {
                scheduled = true
            },
        )

        assertEquals(
            ProjectOpenGradleLoadResult.Requested(
                GradleProjectLoadRequest.Link(tempDir.toAbsolutePath().normalize()),
            ),
            result,
        )
        assertTrue(scheduled)
    }

    @Test
    fun `Gradle project load refreshes a linked project whose imported model is incomplete`() {
        val project = projectFixture.get()
        val linkedWorkspaceRoot = tempDir.toAbsolutePath().normalize()
        Files.writeString(linkedWorkspaceRoot.resolve("settings.gradle.kts"), "pluginManagement {}\n")
        val gradleSettings = GradleSettings.getInstance(project)
        val previousSettings = gradleSettings.linkedProjectsSettings.toList()
        var scheduled = false

        try {
            gradleSettings.setLinkedProjectsSettings(
                listOf(
                    GradleProjectSettings().apply {
                        externalProjectPath = linkedWorkspaceRoot.toString()
                    },
                ),
            )

            val result = KastProjectOpenGradleLoad.execute(
                project = project,
                workspaceRoot = linkedWorkspaceRoot,
                enabled = ProjectOpenGradleLoadEnabled(true),
                scheduleGradleLoad = {
                    scheduled = true
                },
            )

            assertEquals(
                ProjectOpenGradleLoadResult.Requested(
                    GradleProjectLoadRequest.Refresh(linkedWorkspaceRoot),
                ),
                result,
            )
            assertTrue(scheduled)
        } finally {
            gradleSettings.setLinkedProjectsSettings(previousSettings)
        }
    }

    @Test
    fun `Gradle project load skips a linked project whose imported model is complete`() {
        val project = projectFixture.get()
        val linkedWorkspaceRoot = tempDir.toAbsolutePath().normalize()
        Files.writeString(linkedWorkspaceRoot.resolve("settings.gradle.kts"), "pluginManagement {}\n")
        val gradleSettings = GradleSettings.getInstance(project)
        val previousSettings = gradleSettings.linkedProjectsSettings.toList()
        var scheduled = false

        try {
            gradleSettings.setLinkedProjectsSettings(
                listOf(
                    GradleProjectSettings().apply {
                        externalProjectPath = linkedWorkspaceRoot.toString()
                    },
                ),
            )

            val result = KastProjectOpenGradleLoad.execute(
                project = project,
                workspaceRoot = linkedWorkspaceRoot,
                enabled = ProjectOpenGradleLoadEnabled(true),
                isImportedGradleModelComplete = { _, _ -> true },
                scheduleGradleLoad = {
                    scheduled = true
                },
            )

            assertEquals(
                ProjectOpenGradleLoadResult.Skipped("already loaded"),
                result,
            )
            assertFalse(scheduled)
        } finally {
            gradleSettings.setLinkedProjectsSettings(previousSettings)
        }
    }

    @Test
    fun `project open skips Gradle load when project open Gradle load is disabled`() {
        val project = projectFixture.get()
        var requestedGradleLoad = false
        var startedProject: Project? = null
        val disabledConfig = KastConfig.defaults().copy(
            projectOpen = ProjectOpenConfig(
                profileAutoInit = ProjectOpenProfileAutoInit(true),
                profile = ProjectOpenProfile(ProjectOpenProfile.JETBRAINS_PLUGIN),
                autoExcludeGit = ProjectOpenAutoExcludeGit(true),
                gradleLoadEnabled = ProjectOpenGradleLoadEnabled(false),
            ),
        )

        val started = KastProjectOpenAutoIndexing.execute(
            project = project,
            config = disabledConfig,
            loadGradleProject = { workspaceRoot, config, onComplete ->
                requestedGradleLoad = true
                KastProjectOpenGradleLoad.execute(
                    project,
                    workspaceRoot,
                    config.projectOpen.gradleLoadEnabled,
                    onComplete,
                )
            },
            startBackend = { startupProject, _ -> startedProject = startupProject },
            startReferenceIndex = { startedProject = it },
            restartBackend = {},
        )

        assertTrue(started)
        assertFalse(requestedGradleLoad)
        assertSame(project, startedProject)
    }

    @Test
    fun `project open skips backend and reference indexing when idea backend is disabled`() {
        val project = projectFixture.get()
        var started = false
        val disabledConfig = KastConfig.defaults().let { config ->
            config.copy(
                backends = config.backends.copy(
                    idea = config.backends.idea.copy(enabled = IdeaBackendEnabled(false)),
                ),
            )
        }

        val requestedStart = KastProjectOpenAutoIndexing.execute(
            project = project,
            config = disabledConfig,
            startBackend = { _, _ -> started = true },
            startReferenceIndex = { started = true },
            restartBackend = {},
        )

        assertFalse(requestedStart)
        assertFalse(started)
    }

    @Test
    fun `optional profile setting does not control IDEA backend orchestration`() {
        val project = projectFixture.get()
        val defaults = KastConfig.defaults()
        val config = defaults.copy(
            projectOpen = defaults.projectOpen.copy(
                profileAutoInit = ProjectOpenProfileAutoInit(false),
                gradleLoadEnabled = ProjectOpenGradleLoadEnabled(false),
            ),
        )
        var backendStarted = false
        var indexStarted = false

        val requestedStart = KastProjectOpenAutoIndexing.execute(
            project = project,
            config = config,
            startBackend = { _, startupConfig ->
                assertSame(config, startupConfig)
                backendStarted = true
            },
            startReferenceIndex = { indexStarted = true },
            restartBackend = {},
        )

        assertTrue(requestedStart)
        assertTrue(backendStarted)
        assertTrue(indexStarted)
    }

    @Test
    fun `backend startup failure is contained before Gradle load and indexing`() {
        val project = projectFixture.get()
        var requestedGradleLoad = false
        var requestedIndex = false

        val requestedStart = KastProjectOpenAutoIndexing.execute(
            project = project,
            config = KastConfig.defaults(),
            loadGradleProject = { _, _, _ ->
                requestedGradleLoad = true
                error("Gradle load must not run after backend startup fails")
            },
            startBackend = { _, _ -> error("compatibility metadata failed") },
            startReferenceIndex = { requestedIndex = true },
            restartBackend = {},
        )

        assertFalse(requestedStart)
        assertFalse(requestedGradleLoad)
        assertFalse(requestedIndex)
    }

}
