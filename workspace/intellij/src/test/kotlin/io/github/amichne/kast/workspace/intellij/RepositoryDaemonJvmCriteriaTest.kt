package io.github.amichne.kast.workspace.intellij

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RepositoryDaemonJvmCriteriaTest {
    @Test
    fun `contained criteria links are admitted but dangling links reject`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        Files.createDirectories(root.resolve("gradle"))
        Files.writeString(root.resolve("shared.properties"), "toolchainVersion=21\n")
        val link =
            Files.createSymbolicLink(
                root.resolve("gradle/gradle-daemon-jvm.properties"),
                Path.of("../shared.properties"),
            )
        assertInstanceOf(
            RepositoryDaemonJvmCriteria.Required::class.java,
            repositoryDaemonJvmCriteria(root, org.gradle.util.GradleVersion.version("9.4.1")),
        )
        Files.delete(root.resolve("shared.properties"))
        assertEquals(
            RepositoryDaemonJvmCriteria.Rejected,
            repositoryDaemonJvmCriteria(root, org.gradle.util.GradleVersion.version("9.4.1")),
        )
        org.junit.jupiter.api.Assertions.assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun `generated version criteria is admitted for supported Gradle`(@TempDir root: Path) {
        val gradle = Files.createDirectories(root.resolve("gradle"))
        Files.writeString(
            gradle.resolve("gradle-daemon-jvm.properties"),
            """
            toolchainUrl.MAC_OS.AARCH64=https\://example.invalid/jdk
            toolchainVersion=17
            """
                .trimIndent(),
        )

        val criteria =
            assertInstanceOf(
                RepositoryDaemonJvmCriteria.Required::class.java,
                repositoryDaemonJvmCriteria(root, GradleVersion.version("8.8")),
            )

        assertEquals(JavaFeature.of(17), criteria.feature)
    }

    @Test
    fun `unsupported vendor criteria fails closed`(@TempDir root: Path) {
        val gradle = Files.createDirectories(root.resolve("gradle"))
        Files.writeString(
            gradle.resolve("gradle-daemon-jvm.properties"),
            "toolchainVersion=21\ntoolchainVendor=JETBRAINS\n",
        )

        assertEquals(
            RepositoryDaemonJvmCriteria.Rejected,
            repositoryDaemonJvmCriteria(root, GradleVersion.version("9.1.0")),
        )
    }

    @Test
    fun `older Gradle does not acquire unsupported daemon criteria authority`(@TempDir root: Path) {
        val gradle = Files.createDirectories(root.resolve("gradle"))
        Files.writeString(
            gradle.resolve("gradle-daemon-jvm.properties"),
            "toolchainVersion=17\n",
        )

        assertEquals(
            RepositoryDaemonJvmCriteria.Absent,
            repositoryDaemonJvmCriteria(root, GradleVersion.version("8.7")),
        )
    }
}
