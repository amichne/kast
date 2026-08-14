package support.architecture.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.architecture.ArchitecturePolicyValidation
import support.architecture.KastArchitecturePolicy
import support.architecture.ModuleRoleConvention
import support.architecture.projection.ArchitectureProjection
import java.nio.file.Files
import java.nio.file.Path

class KastArchitecturePluginFunctionalTest {
    @Test
    fun `every declared role convention plugin is loadable and publishes its exact marker`(
        @TempDir fixture: Path,
    ) {
        val conventions = ModuleRoleConvention.entries
        conventions.forEachIndexed { index, convention ->
            val projectName = "role$index"
            val project = fixture.resolve(projectName)
            Files.createDirectories(project)
            Files.writeString(
                project.resolve("build.gradle.kts"),
                """
                plugins {
                    id("${convention.pluginId}")
                }

                tasks.register("verifyRoleConvention") {
                    doLast {
                        check(project.extensions.extraProperties["kast.moduleRole"] == "${convention.role.name}")
                    }
                }
                """.trimIndent(),
            )
        }
        Files.writeString(
            fixture.resolve("settings.gradle.kts"),
            """
            rootProject.name = "role-convention-fixture"
            ${conventions.indices.joinToString("\n") { index -> "include(\":role$index\")" }}
            """.trimIndent(),
        )
        Files.writeString(
            fixture.resolve("build.gradle.kts"),
            """
            plugins {
                base
            }

            tasks.register("verifyRoleConventions") {
                dependsOn(${conventions.indices.joinToString { index -> "\"role$index:verifyRoleConvention\"" }})
            }
            """.trimIndent(),
        )

        roleRunner(fixture).build()
    }

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
    fun `exported implementation dependency fails the owning Gradle gate`(@TempDir fixture: Path) {
        val projects = listOf(
            "analysis-api",
            "analysis-server",
            "index-store",
            "indexer",
            "symbol-contract",
            "symbol-intellij",
        )
        projects.forEach { Files.createDirectories(fixture.resolve(it)) }
        Files.createDirectories(fixture.resolve("symbol"))
        Files.writeString(
            fixture.resolve("settings.gradle.kts"),
            """
            rootProject.name = "architecture-export-fixture"
            include(
                ":analysis-api",
                ":analysis-server",
                ":index-store",
                ":indexer",
                ":symbol:contract",
                ":symbol:intellij",
            )
            project(":symbol:contract").projectDir = file("symbol-contract")
            project(":symbol:intellij").projectDir = file("symbol-intellij")
            """.trimIndent(),
        )
        Files.writeString(
            fixture.resolve("build.gradle.kts"),
            """
            plugins {
                id("kast.architecture")
            }

            subprojects {
                if (path != ":symbol") {
                    apply(plugin = "java")
                }
            }

            project(":symbol:contract") {
                apply(plugin = "kast.role.contract")
            }
            project(":symbol:intellij") {
                apply(plugin = "kast.role.intellij-read")
                dependencies {
                    add("api", project(":symbol:contract"))
                }
            }
            """.trimIndent(),
        )
        writeProjection(fixture)

        val result = runner(fixture).buildAndFail()

        assertTrue(result.output.contains("FORBIDDEN_EXPORTED_PROJECT_DEPENDENCY"), result.output)
    }

    @Test
    fun `baseline audit reuses configuration cache`(@TempDir fixture: Path) {
        writeFixture(fixture, "")

        runner(fixture).buildAndFail()
        val reused = runner(fixture).buildAndFail()

        assertTrue(reused.output.contains("Configuration cache entry reused"), reused.output)
        assertTrue(reused.output.contains("OBSOLETE_LEGACY_ALLOWANCE"), reused.output)
    }

    @Test
    fun `projection generator and verifier compose without an implicit dependency`(
        @TempDir fixture: Path,
    ) {
        writeFixture(fixture, "")

        val result = combinedRunner(fixture).buildAndFail()

        assertFalse(result.output.contains("uses this output of task"), result.output)
        assertTrue(result.output.contains("OBSOLETE_LEGACY_ALLOWANCE"), result.output)
    }

    private fun runner(fixture: Path): GradleRunner = GradleRunner.create()
        .withProjectDir(fixture.toFile())
        .withPluginClasspath()
        .withArguments("verifyKastArchitecture", "--configuration-cache", "--stacktrace")

    private fun roleRunner(fixture: Path): GradleRunner = GradleRunner.create()
        .withProjectDir(fixture.toFile())
        .withPluginClasspath()
        .withArguments("verifyRoleConventions", "--stacktrace")

    private fun combinedRunner(fixture: Path): GradleRunner = GradleRunner.create()
        .withProjectDir(fixture.toFile())
        .withPluginClasspath()
        .withArguments(
            "generateKastArchitectureProjection",
            "verifyKastArchitecture",
            "--configuration-cache",
            "--stacktrace",
        )

    private fun writeFixture(
        fixture: Path,
        additionalBuild: String,
    ) {
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
        writeProjection(fixture)
    }

    private fun writeProjection(fixture: Path) {
        val architecture =
            (KastArchitecturePolicy.validate() as ArchitecturePolicyValidation.Valid).architecture
        val projection = fixture.resolve("gradle/architecture/kast-architecture-policy.json")
        Files.createDirectories(projection.parent)
        Files.writeString(projection, ArchitectureProjection.render(architecture))
    }
}
