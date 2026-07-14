package io.github.amichne.kast.idea

import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleProjectIdentity
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.GradleProjectPath
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.WorkspaceRelativeGradleBuildRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class IdeaGradleFileProvenanceTest {
    @Test
    fun `same Gradle path in root and included builds remains build-qualified`() {
        val rootApp = project(buildRoot = ".", projectPath = ":app")
        val includedApp = project(buildRoot = "included", projectPath = ":app")
        val file = "/workspace/shared/Shared.kt"
        val provenance = IdeaGradleFileProvenance.create(
            listOf(
                module(rootApp, projectDirectory = "/workspace"),
                module(includedApp, projectDirectory = "/workspace/shared"),
            ),
        )

        val update = provenance.applyTo(update(file))

        assertEquals(setOf(rootApp, includedApp), update.gradleProjects)
    }

    @Test
    fun `custom source set is selected only by model-owned roots`() {
        val app = project(buildRoot = ".", projectPath = ":app")
        val integrationTest = IdeaGradleSourceSetProvenance(
            name = GradleSourceSetName.parse("integrationTest"),
            sourceRoots = setOf(Path.of("/workspace/app/quality/kotlin")),
        )
        val provenance = IdeaGradleFileProvenance.create(
            listOf(
                IdeaGradleModuleProvenance(
                    project = app,
                    projectDirectory = Path.of("/workspace/app"),
                    sourceSets = setOf(integrationTest),
                ),
            ),
        )

        val custom = provenance.applyTo(update("/workspace/app/quality/kotlin/Contract.kt"))
        val conventionalButUnproven = provenance.applyTo(update("/workspace/app/src/main/kotlin/App.kt"))

        assertEquals(setOf("integrationTest"), custom.gradleSourceSets.map { it.sourceSet.value }.toSet())
        assertTrue(conventionalButUnproven.gradleSourceSets.isEmpty())
        assertEquals(setOf(app), conventionalButUnproven.gradleProjects)
    }

    @Test
    fun `missing model association preserves only legacy labels`() {
        val update = update("/workspace/app/src/main/kotlin/App.kt").copy(
            modulePath = ":app[main]",
            sourceSet = "main",
        )

        val result = IdeaGradleFileProvenance.create(emptyList()).applyTo(update)

        assertTrue(result.gradleProjects.isEmpty())
        assertTrue(result.gradleSourceSets.isEmpty())
        assertEquals(":app[main]", result.modulePath)
        assertEquals("main", result.sourceSet)
    }

    private fun module(
        project: BuildQualifiedGradleProjectIdentity,
        projectDirectory: String,
    ): IdeaGradleModuleProvenance = IdeaGradleModuleProvenance(
        project = project,
        projectDirectory = Path.of(projectDirectory),
        sourceSets = emptySet(),
    )

    private fun project(
        buildRoot: String,
        projectPath: String,
    ): BuildQualifiedGradleProjectIdentity = BuildQualifiedGradleProjectIdentity(
        buildRoot = WorkspaceRelativeGradleBuildRoot.parse(buildRoot),
        projectPath = GradleProjectPath.parse(projectPath),
    )

    private fun update(path: String): FileIndexUpdate = FileIndexUpdate(
        path = path,
        identifiers = emptySet(),
        packageName = null,
        modulePath = null,
        sourceSet = null,
        imports = emptySet(),
        wildcardImports = emptySet(),
    )
}
