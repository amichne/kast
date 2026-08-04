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
}
