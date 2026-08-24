package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerImport
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerModel
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerModelBuilder
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerModelRead
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerProvenance
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProvenanceAuthority
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProvenanceResolution
import io.github.amichne.kast.workspace.intellij.provenance.captureGradleSourceRootProducerImport
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaProject
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader

class GradleProducerProvenanceImportTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `installed producer model is an IntelliJ-discoverable builder service`() {
        assertTrue(
            ModelBuilderService::class.java.isAssignableFrom(
                GradleSourceRootProducerModelBuilder::class.java,
            ),
            "The installed IntelliJ import discovers ModelBuilderService providers, not plain " +
                "ToolingModelBuilder implementations",
        )
        val providers = ServiceLoader.load(
            ModelBuilderService::class.java,
            GradleSourceRootProducerModelBuilder::class.java.classLoader,
        ).toList()
        assertTrue(
            providers.any { provider ->
                provider.javaClass == GradleSourceRootProducerModelBuilder::class.java
            },
            "The producer model builder must be registered as a service provider",
        )
    }

    @Test
    fun `real Gradle IDEA model preserves producer provenance including Kotlin DSL roots`() {
        val physicalProjectDirectory = projectDirectory.toRealPath()
        val authoredLookingGenerated = physicalProjectDirectory.resolve("src/producer-owned")
            .toAbsolutePath().normalize()
        val generatedLookingAuthored = physicalProjectDirectory.resolve("build/authored-source")
            .toAbsolutePath().normalize()
        val testFixturesRoot = physicalProjectDirectory.resolve("src/testFixtures/java")
            .toAbsolutePath().normalize()
        val unrelatedTaskMarker = physicalProjectDirectory.resolve("unrelated-task-configured")
        createGradleFixture(
            authoredLookingGenerated,
            generatedLookingAuthored,
            testFixturesRoot,
        )

        val models = loadGradleModels(unrelatedTaskMarker)
        val imported = models.ideaProject.modules
            .single { module -> module.name == "producer-provenance-fixture" }
            .let { module ->
                captureGradleSourceRootProducerImport(
                    module,
                    GradleSourceRootProducerModelRead.Available(models.producerModel),
                )
            }
        val capture = assertInstanceOf<GradleSourceRootProducerImport.Captured>(imported)
        val authority = GradleSourceRootProvenanceAuthority.compile(listOf(capture))

        assertEquals(
            WorkspaceSourceRootProvenance.GENERATED,
            authority.provenance(authoredLookingGenerated),
        )
        assertEquals(
            WorkspaceSourceRootProvenance.AUTHORED,
            authority.provenance(generatedLookingAuthored),
        )
        assertEquals(
            WorkspaceSourceRootProvenance.AUTHORED,
            authority.provenance(testFixturesRoot),
            "The producer model must retain test-fixture roots outside the standard IDEA model",
        )
        val kotlinDslGenerated = capture.entries.filter { evidence ->
            evidence.provenance == GradleSourceRootProducerProvenance.GENERATED &&
            evidence.sourceRoot.toPath() != authoredLookingGenerated
        }
        assertTrue(
            kotlinDslGenerated.isNotEmpty(),
            "The kotlin-dsl plugin must expose generated IDEA evidence: ${capture.entries}",
        )
        kotlinDslGenerated.forEach { evidence ->
            assertEquals(
                WorkspaceSourceRootProvenance.GENERATED,
                authority.provenance(evidence.sourceRoot.toPath()),
            )
        }
    }

    private fun createGradleFixture(
        authoredLookingGenerated: Path,
        generatedLookingAuthored: Path,
        testFixturesRoot: Path,
    ) {
        Files.writeString(
            projectDirectory.resolve("settings.gradle.kts"),
            "rootProject.name = \"producer-provenance-fixture\"\n",
        )
        Files.writeString(
            projectDirectory.resolve("build.gradle.kts"),
            """
            plugins {
                `kotlin-dsl`
                `java-test-fixtures`
                idea
            }

            repositories {
                gradlePluginPortal()
            }

            sourceSets.main {
                val generatedSourceRoot = layout.projectDirectory.dir("src/producer-owned")
                val generateFixtureSource by tasks.registering {
                    outputs.dir(generatedSourceRoot)
                }
                java.srcDir(generateFixtureSource.map { generatedSourceRoot })
                java.srcDir("build/authored-source")
            }

            tasks.register("unrelatedImportCostProbe") {
                file("unrelated-task-configured").writeText("configured")
            }
            """.trimIndent(),
        )
        Files.createDirectories(authoredLookingGenerated.resolve("fixture"))
        Files.writeString(
            authoredLookingGenerated.resolve("fixture/Generated.java"),
            "package fixture; public final class Generated {}\n",
        )
        Files.createDirectories(generatedLookingAuthored.resolve("fixture"))
        Files.writeString(
            generatedLookingAuthored.resolve("fixture/Authored.java"),
            "package fixture; public final class Authored {}\n",
        )
        Files.createDirectories(testFixturesRoot.resolve("fixture"))
        Files.writeString(
            testFixturesRoot.resolve("fixture/TestFixture.java"),
            "package fixture; public final class TestFixture {}\n",
        )
        val precompiledScript = projectDirectory.resolve("src/main/kotlin/fixture-conventions.gradle.kts")
        Files.createDirectories(precompiledScript.parent)
        Files.writeString(precompiledScript, "plugins { java }\n")
    }

    private fun loadGradleModels(unrelatedTaskMarker: Path): ImportedGradleModels {
        val initScript = projectDirectory.resolve("producer-model.init.gradle")
        val toolingClasses = Path.of(
            GradleSourceRootProducerModelBuilder::class.java
                .protectionDomain.codeSource.location.toURI(),
        )
        val modelBuilderApi = Path.of(
            ModelBuilderService::class.java.protectionDomain.codeSource.location.toURI(),
        )
        Files.writeString(
            initScript,
            """
            initscript {
                dependencies {
                    classpath files(
                        '${toolingClasses.toString().replace("'", "\\'")}',
                        '${modelBuilderApi.toString().replace("'", "\\'")}',
                    )
                }
            }

            import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerModelBuilder
            import javax.inject.Inject
            import org.gradle.api.Plugin
            import org.gradle.api.Project
            import org.gradle.tooling.provider.model.ToolingModelBuilder
            import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry

            class KastProducerModelAdapter implements ToolingModelBuilder {
                private final GradleSourceRootProducerModelBuilder delegate =
                    new GradleSourceRootProducerModelBuilder()

                boolean canBuild(String modelName) {
                    delegate.canBuild(modelName)
                }

                Object buildAll(String modelName, Project project) {
                    delegate.buildAll(modelName, project)
                }
            }

            class KastProducerModelPlugin implements Plugin<Project> {
                private final ToolingModelBuilderRegistry registry

                @Inject
                KastProducerModelPlugin(ToolingModelBuilderRegistry registry) {
                    this.registry = registry
                }

                void apply(Project project) {
                    registry.register(new KastProducerModelAdapter())
                }
            }

            rootProject {
                pluginManager.apply(KastProducerModelPlugin)
            }
            """.trimIndent(),
        )
        val connector = GradleConnector.newConnector()
            .forProjectDirectory(projectDirectory.toRealPath().toFile())
            .useGradleVersion("9.4.1")
        return connector.connect().use { connection ->
            val arguments = listOf("--init-script", initScript.toString())
            val producerModel = connection.model(GradleSourceRootProducerModel::class.java)
                .withArguments(arguments)
                .get()
            assertFalse(
                Files.exists(unrelatedTaskMarker),
                "Producer-model capture must not realize unrelated Gradle tasks",
            )
            ImportedGradleModels(
                ideaProject = connection.model(IdeaProject::class.java)
                    .withArguments(arguments)
                    .get(),
                producerModel = producerModel,
            )
        }
    }

    private fun GradleSourceRootProvenanceAuthority.provenance(
        path: Path,
    ): WorkspaceSourceRootProvenance =
        assertInstanceOf<GradleSourceRootProvenanceResolution.Proven>(
            resolve(path, ExternalSystemSourceType.SOURCE),
        ).provenance

    private data class ImportedGradleModels(
        val ideaProject: IdeaProject,
        val producerModel: GradleSourceRootProducerModel,
    )
}
