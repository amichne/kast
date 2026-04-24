package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstallServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val service = InstallService()

    @Test
    fun `install with explicit instance name creates instance dir and launcher`() {
        val archive = buildFakeArchive(tempDir.resolve("kast.zip"))
        val instancesRoot = tempDir.resolve("instances")
        val binDir = tempDir.resolve("bin")

        val result = service.install(
            InstallOptions(
                archivePath = archive,
                instanceName = "my-dev",
                instancesRoot = instancesRoot,
                binDir = binDir,
            ),
        )

        assertEquals("my-dev", result.instanceName)
        assertTrue(Files.isDirectory(instancesRoot.resolve("my-dev")))
        assertTrue(Files.isRegularFile(instancesRoot.resolve("my-dev/kast")))
        assertTrue(Files.isRegularFile(instancesRoot.resolve("my-dev/bin/kast")))
        assertTrue(Files.isRegularFile(binDir.resolve("kast-my-dev")))
        assertTrue(binDir.resolve("kast-my-dev").toFile().canExecute())

        val launcherContent = binDir.resolve("kast-my-dev").toFile().readText()
        assertTrue(launcherContent.contains("#!/usr/bin/env bash"))
        assertTrue(launcherContent.contains("my-dev/kast"))
    }

    @Test
    fun `install without instance name uses generated name`() {
        val archive = buildFakeArchive(tempDir.resolve("kast.zip"))
        val instancesRoot = tempDir.resolve("instances")
        val binDir = tempDir.resolve("bin")

        val result = service.install(
            InstallOptions(
                archivePath = archive,
                instanceName = null,
                instancesRoot = instancesRoot,
                binDir = binDir,
            ),
        )

        assertTrue(result.instanceName.isNotEmpty())
        assertTrue(result.instanceName.matches(Regex("[a-z]+-[a-z]+")))
        assertTrue(Files.isDirectory(instancesRoot.resolve(result.instanceName)))
        assertTrue(Files.isRegularFile(binDir.resolve("kast-${result.instanceName}")))
    }

    @Test
    fun `install overwrites existing instance with same name`() {
        val archive = buildFakeArchive(tempDir.resolve("kast.zip"))
        val instancesRoot = tempDir.resolve("instances")
        val binDir = tempDir.resolve("bin")
        val options = InstallOptions(
            archivePath = archive,
            instanceName = "reuse",
            instancesRoot = instancesRoot,
            binDir = binDir,
        )

        service.install(options)
        val result = service.install(options)

        assertEquals("reuse", result.instanceName)
        assertTrue(Files.isRegularFile(instancesRoot.resolve("reuse/kast")))
    }

    @Test
    fun `install with missing archive throws CliFailure`() {
        val failure = assertThrows<CliFailure> {
            service.install(
                InstallOptions(
                    archivePath = tempDir.resolve("missing.zip"),
                    instanceName = "test",
                    instancesRoot = tempDir.resolve("instances"),
                    binDir = tempDir.resolve("bin"),
                ),
            )
        }
        assertEquals("INSTALL_ERROR", failure.code)
        assertTrue(failure.message.contains("Archive not found"))
    }

    @Test
    fun `install with archive lacking kast dir throws CliFailure`() {
        val emptyZip = tempDir.resolve("empty.zip")
        ZipOutputStream(emptyZip.toFile().outputStream()).use { /* empty */ }

        val failure = assertThrows<CliFailure> {
            service.install(
                InstallOptions(
                    archivePath = emptyZip,
                    instanceName = "test",
                    instancesRoot = tempDir.resolve("instances"),
                    binDir = tempDir.resolve("bin"),
                ),
            )
        }
        assertEquals("INSTALL_ERROR", failure.code)
        assertTrue(failure.message.contains("kast/ directory"))
    }

    @Test
    fun `install with invalid instance name throws CliFailure`() {
        val archive = buildFakeArchive(tempDir.resolve("kast.zip"))

        val failure = assertThrows<CliFailure> {
            service.install(
                InstallOptions(
                    archivePath = archive,
                    instanceName = "bad name!",
                    instancesRoot = tempDir.resolve("instances"),
                    binDir = tempDir.resolve("bin"),
                ),
            )
        }
        assertEquals("INSTALL_ERROR", failure.code)
        assertTrue(failure.message.contains("Instance name"))
    }

    @Test
    fun `install command parses from CLI args`() {
        val archive = buildFakeArchive(tempDir.resolve("kast.zip"))
        val instancesRoot = tempDir.resolve("instances")
        val binDir = tempDir.resolve("bin")
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val cli = KastCli.testInstance(
            commandExecutorFactory = { _ ->
                object : CliCommandExecutor {
                    override suspend fun execute(command: CliCommand): CliExecutionResult {
                        check(command is CliCommand.Install)
                        return CliExecutionResult(
                            output = CliOutput.JsonValue(
                                InstallResult(
                                    instanceName = command.options.instanceName ?: "generated",
                                    instanceRoot = command.options.instancesRoot.resolve("generated").toString(),
                                    launcherPath = command.options.binDir.resolve("kast-generated").toString(),
                                ),
                            ),
                        )
                    }
                }
            },
        )

        val exitCode = cli.run(
            arrayOf(
                "install",
                "--archive=$archive",
                "--instance=my-dev",
                "--instances-root=$instancesRoot",
                "--bin-dir=$binDir",
            ),
            stdout,
            stderr,
        )

        assertEquals(0, exitCode)
        assertTrue(stdout.toString().contains("my-dev"))
    }

    private fun buildFakeArchive(destination: Path): Path {
        ZipOutputStream(destination.toFile().outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("kast/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("kast/kast"))
            zip.write("#!/usr/bin/env bash\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("kast/bin/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("kast/bin/kast"))
            zip.write("#!/usr/bin/env bash\n".toByteArray())
            zip.closeEntry()
        }
        return destination
    }
}
