package io.github.amichne.kast.workspace.intellij

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectGradleJvmAuthorityTest {
    @Test
    fun `explicit repository JVM is admitted physically before IntelliJ selection`(@TempDir root: Path) {
        val home = Files.createDirectories(root.resolve("jdk")).toRealPath()
        val java = Files.createDirectories(home.resolve("bin")).resolve("java")
        Files.writeString(java, "test executable")
        assertTrue(java.toFile().setExecutable(true))
        Files.writeString(root.resolve("gradle.properties"), "org.gradle.java.home=$home\n")
        assertEquals(home, (projectGradleJvmAuthority(root) as ProjectGradleJvmAuthority.Present).home)
    }

    @Test
    fun `invalid configured JVM does not fall back to automatic selection`(@TempDir root: Path) {
        assertEquals(ProjectGradleJvmAuthority.Absent, projectGradleJvmAuthority(root))
        for (value in listOf("relative", root.toRealPath().toString())) {
            Files.writeString(root.resolve("gradle.properties"), "org.gradle.java.home=$value\n")
            assertEquals(ProjectGradleJvmAuthority.Rejected, projectGradleJvmAuthority(root))
        }
    }
}
