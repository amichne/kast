package io.github.amichne.kast.idea.transition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BuildSemanticInputIdentityResolverTest {
    @TempDir
    lateinit var buildRoot: Path

    @Test
    fun `nested build logic and catalogs affect the resolved Gradle root identity`() {
        Files.writeString(buildRoot.resolve("settings.gradle.kts"), "rootProject.name = \"demo\"")
        val catalog = buildRoot.resolve("included/gradle/libs.versions.toml").also { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, "[versions]\nkotlin = \"2.3.20\"")
        }
        val resolver = BuildSemanticInputIdentityResolver(buildRoot)
        val before = resolver.resolve()

        Files.writeString(catalog, "[versions]\nkotlin = \"2.3.21\"")

        assertNotEquals(before, resolver.resolve())
    }

    @Test
    fun `scope policy does not masquerade as imported Gradle model input`() {
        Files.writeString(buildRoot.resolve("settings.gradle.kts"), "rootProject.name = \"demo\"")
        val ignore = buildRoot.resolve("modules/app/.kastignore").also { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, "generated/**\n")
        }
        val resolver = BuildSemanticInputIdentityResolver(buildRoot)
        val before = resolver.resolve()

        Files.writeString(ignore, "fixtures/**\n")

        assertEquals(before, resolver.resolve())
    }

    @Test
    fun `Gradle locks local properties and build logic sources affect identity`() {
        Files.writeString(buildRoot.resolve("settings.gradle.kts"), "rootProject.name = \"demo\"")
        val inputs = listOf(
            buildRoot.resolve("local.properties"),
            buildRoot.resolve("gradle.lockfile"),
            buildRoot.resolve("gradle/dependency-locks/runtime.lockfile"),
            buildRoot.resolve("buildSrc/src/main/groovy/Plugin.groovy"),
            buildRoot.resolve("buildSrc/src/main/kotlin/Plugin.kt"),
            buildRoot.resolve("build-logic/src/main/java/Plugin.java"),
        )
        inputs.forEach { input ->
            Files.createDirectories(input.parent)
            Files.writeString(input, "before")
        }
        val resolver = BuildSemanticInputIdentityResolver(buildRoot)

        inputs.forEach { input ->
            val before = resolver.resolve()
            Files.writeString(input, "after:${input.fileName}")

            assertNotEquals(before, resolver.resolve(), input.toString())
        }
    }

    @Test
    fun `unrelated build logic metadata does not affect identity`() {
        Files.writeString(buildRoot.resolve("settings.gradle.kts"), "rootProject.name = \"demo\"")
        val metadata = buildRoot.resolve("buildSrc/README.md").also { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, "before")
        }
        val resolver = BuildSemanticInputIdentityResolver(buildRoot)
        val before = resolver.resolve()

        Files.writeString(metadata, "after")

        assertEquals(before, resolver.resolve())
    }
}
