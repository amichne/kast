package io.github.amichne.kast.idea

import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleProjectIdentity
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.GradleProjectPath
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.WorkspaceRelativeGradleBuildRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
                module("root-app", rootApp),
                module("included-app", includedApp),
            ),
        )

        val update = provenance.applyTo(
            update = update(file),
            ownerModuleNames = setOf(moduleIdentity("root-app"), moduleIdentity("included-app")),
        )

        assertEquals(setOf(rootApp, includedApp), update.gradleProjects)
    }

    @Test
    fun `project directory ancestry cannot become model-proven ownership`() {
        val rootApp = project(buildRoot = ".", projectPath = ":")
        val includedApp = project(buildRoot = "included", projectPath = ":app")
        val provenance = IdeaGradleFileProvenance.create(
            listOf(
                module("root", rootApp),
                module("included-app", includedApp),
            ),
        )

        val update = provenance.applyTo(
            update = update("/workspace/included/app/src/main/kotlin/App.kt"),
            ownerModuleNames = setOf(moduleIdentity("included-app")),
        )

        assertEquals(setOf(includedApp), update.gradleProjects)
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
                    ideaModuleIdentity = moduleIdentity("app"),
                    project = app,
                    sourceSets = setOf(integrationTest),
                ),
            ),
        )

        val owners = setOf(moduleIdentity("app"))
        val custom = provenance.applyTo(update("/workspace/app/quality/kotlin/Contract.kt"), owners)
        val conventionalButUnproven = provenance.applyTo(update("/workspace/app/src/main/kotlin/App.kt"), owners)

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

        val result = IdeaGradleFileProvenance.create(emptyList()).applyTo(
            update = update,
            ownerModuleNames = setOf(moduleIdentity("app")),
        )

        assertTrue(result.gradleProjects.isEmpty())
        assertTrue(result.gradleSourceSets.isEmpty())
        assertEquals(":app[main]", result.modulePath)
        assertEquals("main", result.sourceSet)
    }

    private fun module(
        ideaModuleName: String,
        project: BuildQualifiedGradleProjectIdentity,
    ): IdeaGradleModuleProvenance = IdeaGradleModuleProvenance(
        ideaModuleIdentity = moduleIdentity(ideaModuleName),
        project = project,
        sourceSets = emptySet(),
    )

    private fun moduleIdentity(value: String): IdeaWorkspaceModuleIdentity =
        IdeaWorkspaceModuleIdentity.of(value)

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

class IdeaGradleProjectLoadBridgeSourceRootTest {
    @Test
    fun `Gradle bridge preserves source-root provenance from model evidence`() {
        listOf(
            Path.of("/workspace/build/generated/authored-by-model"),
            Path.of("/workspace/buildSrc/src/main/kotlin"),
            Path.of("/workspace/build-logic/src/main/kotlin"),
        ).forEach { path ->
            val root = IdeaGradleProjectLoadBridge.classifySourceRoot(
                path,
                listOf(ExternalSystemSourceType.SOURCE),
            )
            val authored = assertInstanceOf(
                IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Authored::class.java,
                root.provenance(),
            )
            assertEquals(listOf("SOURCE"), authored.modelEvidence().map { it.name })
        }

        val generated = IdeaGradleProjectLoadBridge.classifySourceRoot(
            Path.of("/workspace/generated-outside-build/kotlin"),
            listOf(ExternalSystemSourceType.SOURCE_GENERATED),
        )
        val generatedProvenance = assertInstanceOf(
            IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Generated::class.java,
            generated.provenance(),
        )
        assertEquals(
            listOf("SOURCE_GENERATED"),
            generatedProvenance.modelEvidence().map { it.name },
        )

        val unknown = IdeaGradleProjectLoadBridge.classifySourceRoot(
            Path.of("/workspace/unclassified/kotlin"),
            emptyList(),
        )
        val unknownProvenance = assertInstanceOf(
            IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Unknown::class.java,
            unknown.provenance(),
        )
        assertEquals("Gradle model supplied no source-type classification", unknownProvenance.reason())
        assertEquals(emptyList<String>(), unknownProvenance.modelEvidence().map { it.name })
    }
}
