package io.github.amichne.kast.workspace.intellij

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProjectGradleJvmAuthorityTest {
    @Test
    fun `contained properties link without JVM override remains automatic`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        Files.writeString(root.resolve("shared.properties"), "org.gradle.jvmargs=-Xmx1g\n")
        Files.createSymbolicLink(root.resolve("gradle.properties"), Path.of("shared.properties"))
        assertEquals(ProjectGradleJvmAuthority.Absent, projectGradleJvmAuthority(root))
    }

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

    @Test
    fun `invalid properties resolution retains a typed input cause`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val outside = Files.writeString(temporary.resolve("outside.properties"), "org.gradle.jvmargs=-Xmx1g")
        Files.createSymbolicLink(root.resolve("gradle.properties"), outside)
        val rejection = projectGradleJvmAuthority(root) as ProjectGradleJvmAuthority.InputRejected
        val input = rejection.failure as InstalledGradleModelCaptureFailure.ModelInputRejected
        assertEquals(
            io.github.amichne.kast.distribution.contract.bootstrap.ModelInputFailureReason.OUTSIDE_WORKSPACE,
            input.failure.reason,
        )
        assertEquals("gradle.properties", input.failure.path.value)
    }

    @Test
    fun `ambient Java home is optional admitted fallback authority`(@TempDir root: Path) {
        val home = Files.createDirectories(root.resolve("jdk")).toRealPath()
        val java = Files.createDirectories(home.resolve("bin")).resolve("java")
        Files.writeString(java, "test executable")
        assertTrue(java.toFile().setExecutable(true))

        assertEquals(
            home,
            (ambientGradleJvmAuthority(home.toString()) as AmbientGradleJvmAuthority.Present).home,
        )
        assertEquals(AmbientGradleJvmAuthority.Absent, ambientGradleJvmAuthority(null))
        assertEquals(AmbientGradleJvmAuthority.Rejected, ambientGradleJvmAuthority("relative"))
    }
}
