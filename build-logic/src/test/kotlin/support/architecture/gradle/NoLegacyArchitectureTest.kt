package support.architecture.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class NoLegacyArchitectureTest {
    @Test
    fun `legacy observations become deterministic finite findings`() {
        val observation = LegacyArchitectureObservation(
            projectPaths = setOf(":indexer", ":analysis-api"),
            legacyModuleRoots = setOf("analysis-api", "analysis-server"),
            productionSources = listOf(
                ProductionSource(
                    "indexer/src/main/KastIndexerBackend.kt",
                    "class KastIndexerBackend : AnalysisBackend",
                ),
                ProductionSource(
                    "indexer/src/main/CompatibilityRoute.kt",
                    "class CompatibilityRoute",
                ),
                ProductionSource(
                    "indexer/src/main/FallbackAnalysisBackend.kt",
                    "class FallbackAnalysisBackend",
                ),
            ),
        )

        assertEquals(
            listOf(
                LegacyArchitectureFinding.LegacyModuleRoot("analysis-api"),
                LegacyArchitectureFinding.LegacyModuleRoot("analysis-server"),
                LegacyArchitectureFinding.LegacyProject(":analysis-api"),
                LegacyArchitectureFinding.AnalysisBackendSymbol(
                    "indexer/src/main/FallbackAnalysisBackend.kt",
                ),
                LegacyArchitectureFinding.AnalysisBackendSymbol(
                    "indexer/src/main/KastIndexerBackend.kt",
                ),
                LegacyArchitectureFinding.CompatibilityRoute(
                    "indexer/src/main/CompatibilityRoute.kt",
                ),
                LegacyArchitectureFinding.DuplicateLegacyAuthority(
                    "indexer/src/main/KastIndexerBackend.kt",
                    "KastIndexerBackend",
                ),
                LegacyArchitectureFinding.FallbackAuthority(
                    "indexer/src/main/FallbackAnalysisBackend.kt",
                ),
            ),
            NoLegacyArchitectureInspection.inspect(observation),
        )
    }

    @Test
    fun `target-only observation is accepted`() {
        val observation = LegacyArchitectureObservation(
            projectPaths = setOf(":runtime:composition", ":indexer"),
            legacyModuleRoots = emptySet(),
            productionSources = listOf(
                ProductionSource(
                    "runtime/composition/src/main/KastRuntimeComposition.kt",
                    "class KastRuntimeComposition",
                ),
            ),
        )

        assertEquals(emptyList<LegacyArchitectureFinding>(), NoLegacyArchitectureInspection.inspect(observation))
    }

    @Test
    fun `exact Gradle task rejects legacy fixture and accepts target fixture`(@TempDir fixture: Path) {
        val legacy = fixture.resolve("legacy")
        writeFixture(
            legacy,
            projects = listOf("analysis-api", "indexer"),
            sources = mapOf(
                "analysis-api/src/main/java/AnalysisBackend.java" to "interface AnalysisBackend {}",
                "indexer/src/main/java/CompatibilityRoute.java" to "class CompatibilityRoute {}",
            ),
        )

        val rejection = runner(legacy).buildAndFail()
        assertTrue(rejection.output.contains("LEGACY_MODULE_ROOT analysis-api"), rejection.output)
        assertTrue(rejection.output.contains("LEGACY_PROJECT :analysis-api"), rejection.output)
        assertTrue(rejection.output.contains("ANALYSIS_BACKEND_SYMBOL"), rejection.output)
        assertTrue(rejection.output.contains("COMPATIBILITY_ROUTE"), rejection.output)

        val target = fixture.resolve("target")
        writeFixture(
            target,
            projects = listOf("indexer"),
            sources = mapOf(
                "indexer/src/main/java/KastIndexerHost.java" to "class KastIndexerHost {}",
            ),
        )
        runner(target).build()
    }

    private fun writeFixture(
        fixture: Path,
        projects: List<String>,
        sources: Map<String, String>,
    ) {
        Files.createDirectories(fixture)
        Files.writeString(
            fixture.resolve("settings.gradle.kts"),
            "rootProject.name = \"no-legacy-fixture\"\n" +
            projects.joinToString("\n") { project -> "include(\":$project\")" },
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
            """.trimIndent(),
        )
        sources.forEach { (path, content) ->
            val source = fixture.resolve(path)
            Files.createDirectories(source.parent)
            Files.writeString(source, content)
        }
    }

    private fun runner(fixture: Path): GradleRunner = GradleRunner.create()
        .withProjectDir(fixture.toFile())
        .withPluginClasspath()
        .withArguments("verifyNoLegacyArchitecture", "--stacktrace")
}
