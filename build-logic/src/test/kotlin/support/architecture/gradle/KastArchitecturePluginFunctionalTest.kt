package support.architecture.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.architecture.ArchitecturePolicyValidation
import support.architecture.KastArchitecturePolicy
import support.architecture.projection.ArchitectureProjection
import java.nio.file.Files
import java.nio.file.Path

class KastArchitecturePluginFunctionalTest {
    @Test
    fun `unapproved project dependency fails the owning Gradle gate`(@TempDir fixture: Path) {
        writeFixture(
            fixture,
            """
            project(":analysis-api") {
                dependencies {
                    add("implementation", project(":indexer"))
                }
            }
            """.trimIndent(),
        )

        val result = runner(fixture).buildAndFail()

        assertTrue(result.output.contains("UNAPPROVED_PROJECT_DEPENDENCY"), result.output)
    }

    @Test
    fun `baseline audit reuses configuration cache`(@TempDir fixture: Path) {
        writeFixture(fixture, "")

        runner(fixture).buildAndFail()
        val reused = runner(fixture).buildAndFail()

        assertTrue(reused.output.contains("Configuration cache entry reused"), reused.output)
        assertTrue(reused.output.contains("OBSOLETE_LEGACY_ALLOWANCE"), reused.output)
    }

    private fun runner(fixture: Path): GradleRunner = GradleRunner.create()
        .withProjectDir(fixture.toFile())
        .withPluginClasspath()
        .withArguments("verifyKastArchitecture", "--configuration-cache", "--stacktrace")

    private fun writeFixture(fixture: Path, additionalBuild: String) {
        listOf("analysis-api", "analysis-server", "index-store", "indexer")
            .forEach { Files.createDirectories(fixture.resolve(it)) }
        Files.writeString(
            fixture.resolve("settings.gradle.kts"),
            """
            rootProject.name = "architecture-fixture"
            include(":analysis-api", ":analysis-server", ":index-store", ":indexer")
            """.trimIndent(),
        )
        Files.writeString(
            fixture.resolve("build.gradle.kts"),
            """
            plugins {
                id("kast.architecture")
            }

            subprojects {
                apply(plugin = "java")
            }

            $additionalBuild
            """.trimIndent(),
        )
        val architecture = (KastArchitecturePolicy.validate() as ArchitecturePolicyValidation.Valid).architecture
        val projection = fixture.resolve("gradle/architecture/kast-architecture-policy.json")
        Files.createDirectories(projection.parent)
        Files.writeString(projection, ArchitectureProjection.render(architecture))
    }
}
