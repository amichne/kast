package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile

class ProcessLauncherTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `single fat jar with runtime libs launches on extracted classpath`() {
        val libsDirectory = java.nio.file.Files.createDirectories(tempDir.resolve("libs"))
        val jarPath = libsDirectory.resolve("analysis-cli-0.1.0-SNAPSHOT-all.jar").createFile()
        val runtimeLibs = tempDir.resolve("runtime-libs")
        java.nio.file.Files.createDirectories(runtimeLibs)
        val mainJar = runtimeLibs.resolve("analysis-cli-0.1.0-SNAPSHOT.jar").createFile()
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
        val jarPath = tempDir.resolve("analysis-cli-0.1.0-SNAPSHOT-all.jar").createFile()

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
}
