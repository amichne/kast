package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions

class ProcessLauncherTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `single fat jar with runtime libs launches on extracted classpath`() {
        val libsDirectory = java.nio.file.Files.createDirectories(tempDir.resolve("libs"))
        val jarPath = libsDirectory.resolve("kast-0.1.0-SNAPSHOT-all.jar").createFile()
        val runtimeLibs = tempDir.resolve("runtime-libs")
        java.nio.file.Files.createDirectories(runtimeLibs)
        val mainJar = runtimeLibs.resolve("kast-0.1.0-SNAPSHOT.jar").createFile()
        val depJar = runtimeLibs.resolve("analysis-server-0.1.0-SNAPSHOT.jar").createFile()
        runtimeLibs.resolve("classpath.txt").toFile().writeText(
            listOf(mainJar.fileName.toString(), depJar.fileName.toString()).joinToString("\n", postfix = "\n"),
        )

        val command = detachedJavaCommand(
            javaExecutable = "/usr/bin/java",
            classPath = jarPath.toString(),
            mainClassName = "io.github.amichne.kast.cli.CliMainKt",
            arguments = listOf("internal", "daemon-run", "--workspace-root=/tmp/workspace"),
        )

        assertEquals(
            listOf(
                "/usr/bin/java",
                "-cp",
                listOf(mainJar.toString(), depJar.toString()).joinToString(java.io.File.pathSeparator),
                "io.github.amichne.kast.cli.CliMainKt",
                "internal",
                "daemon-run",
                "--workspace-root=/tmp/workspace",
            ),
            command,
        )
    }

    @Test
    fun `single fat jar classpath launches with java jar mode`() {
        val jarPath = tempDir.resolve("kast-0.1.0-SNAPSHOT-all.jar").createFile()

        val command = detachedJavaCommand(
            javaExecutable = "/usr/bin/java",
            classPath = jarPath.toString(),
            mainClassName = "io.github.amichne.kast.cli.CliMainKt",
            arguments = listOf("internal", "daemon-run", "--workspace-root=/tmp/workspace"),
        )

        assertEquals(
            listOf(
                "/usr/bin/java",
                "-jar",
                jarPath.toString(),
                "internal",
                "daemon-run",
                "--workspace-root=/tmp/workspace",
            ),
            command,
        )
    }

    @Test
    fun `multi entry classpath keeps main class launch mode`() {
        val firstEntry = tempDir.resolve("classes").toString()
        val secondEntry = tempDir.resolve("dependency.jar").createFile().toString()
        val classPath = listOf(firstEntry, secondEntry).joinToString(java.io.File.pathSeparator)

        val command = detachedJavaCommand(
            javaExecutable = "/usr/bin/java",
            classPath = classPath,
            mainClassName = "io.github.amichne.kast.cli.CliMainKt",
            arguments = listOf("internal", "daemon-run"),
        )

        assertEquals(
            listOf(
                "/usr/bin/java",
                "-cp",
                classPath,
                "io.github.amichne.kast.cli.CliMainKt",
                "internal",
                "daemon-run",
            ),
            command,
        )
    }

    @Test
    fun `runtime libs from environment override missing java classpath`() {
        val runtimeLibs = createRuntimeLibs("env-runtime-libs")

        val classPath = resolveDetachedClassPath(
            javaClassPath = null,
            environmentRuntimeLibs = runtimeLibs,
            currentCommandPath = null,
        )

        assertEquals(
            listOf(
                runtimeLibs.resolve("kast-0.1.0-SNAPSHOT.jar").toString(),
                runtimeLibs.resolve("analysis-server-0.1.0-SNAPSHOT.jar").toString(),
            ).joinToString(java.io.File.pathSeparator),
            classPath,
        )
    }

    @Test
    fun `native launcher command path finds sibling runtime libs`() {
        val distRoot = tempDir.resolve("dist").createDirectories()
        val runtimeLibs = createRuntimeLibs(distRoot.resolve("runtime-libs"))
        val nativeBinary = distRoot.resolve("bin").createDirectories().resolve("kast").createFile()

        val classPath = resolveDetachedClassPath(
            javaClassPath = null,
            environmentRuntimeLibs = null,
            currentCommandPath = nativeBinary,
        )

        assertEquals(
            listOf(
                runtimeLibs.resolve("kast-0.1.0-SNAPSHOT.jar").toString(),
                runtimeLibs.resolve("analysis-server-0.1.0-SNAPSHOT.jar").toString(),
            ).joinToString(java.io.File.pathSeparator),
            classPath,
        )
    }

    @Test
    fun `invalid environment runtime libs path fails fast`() {
        val failure = assertThrows<CliFailure> {
            resolveDetachedClassPath(
                javaClassPath = null,
                environmentRuntimeLibs = tempDir.resolve("missing-runtime-libs"),
                currentCommandPath = null,
            )
        }

        assertEquals("DAEMON_START_FAILED", failure.code)
        assertTrue(failure.message.contains("KAST_RUNTIME_LIBS"))
    }

    @Test
    fun `java executable prefers JAVA_HOME when available`() {
        val javaHome = tempDir.resolve("java-home").resolve("bin").createDirectories().resolve("java").createFile()
        javaHome.setPosixFilePermissions(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
        )

        val executable = resolveJavaExecutable(
            javaHomeProperty = null,
            javaHomeEnvironment = tempDir.resolve("java-home").toString(),
        )

        assertEquals(javaHome.toString(), executable)
    }

    @Test
    fun `java executable falls back to java home property`() {
        val javaHome = tempDir.resolve("java-home-prop").resolve("bin").createDirectories().resolve("java").createFile()
        javaHome.setPosixFilePermissions(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
        )

        val executable = resolveJavaExecutable(
            javaHomeProperty = tempDir.resolve("java-home-prop").toString(),
            javaHomeEnvironment = null,
        )

        assertEquals(javaHome.toString(), executable)
    }

    private fun createRuntimeLibs(name: String): Path = createRuntimeLibs(tempDir.resolve(name))

    private fun createRuntimeLibs(runtimeLibs: Path): Path {
        runtimeLibs.createDirectories()
        val mainJar = runtimeLibs.resolve("kast-0.1.0-SNAPSHOT.jar").createFile()
        val depJar = runtimeLibs.resolve("analysis-server-0.1.0-SNAPSHOT.jar").createFile()
        runtimeLibs.resolve("classpath.txt").toFile().writeText(
            listOf(mainJar.fileName.toString(), depJar.fileName.toString()).joinToString("\n", postfix = "\n"),
        )
        return runtimeLibs
    }
}
