package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.options.InstallCopilotExtensionOptions
import io.github.amichne.kast.cli.tty.CliFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstallCopilotExtensionServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun installCopiesBundledCopilotExtensionTreeAndWritesVersionMarker() {
        val targetDir = tempDir.resolve(".github")
        val service = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.2.3"),
        )

        val result = service.install(
            InstallCopilotExtensionOptions(
                targetDir = targetDir,
                force = false,
            ),
        )

        assertEquals(targetDir.toAbsolutePath().normalize().toString(), result.installedAt)
        assertEquals("1.2.3", result.version)
        assertFalse(result.skipped)
        assertTrue(Files.isRegularFile(targetDir.resolve("agents/kast.md")))
        assertTrue(Files.isRegularFile(targetDir.resolve("agents/explore.md")))
        assertTrue(Files.isRegularFile(targetDir.resolve("agents/plan.md")))
        assertTrue(Files.isRegularFile(targetDir.resolve("agents/edit.md")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/hooks.json")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/session-start.sh")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/record-paths.sh")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/require-skills.sh")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/session-end.sh")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/resolve-kast-cli-path.sh")))
        assertEquals("1.2.3", Files.readString(targetDir.resolve(".kast-copilot-version")).trim())
    }

    @Test
    fun installSkipsWhenTheSameVersionIsAlreadyInstalled() {
        val targetDir = tempDir.resolve(".github")
        val service = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.2.3"),
        )
        val options = InstallCopilotExtensionOptions(
            targetDir = targetDir,
            force = false,
        )

        service.install(options)
        val result = service.install(options)

        assertTrue(result.skipped)
        assertEquals("1.2.3", result.version)
    }

    @Test
    fun installOverwritesAnExistingCopilotExtensionDirectoryWhenForced() {
        val targetDir = tempDir.resolve(".github")
        val initialService = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.0.0"),
        )
        val updatedService = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "2.0.0"),
        )

        initialService.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = false))
        Files.writeString(targetDir.resolve("stale.txt"), "old")

        val result = updatedService.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = true))

        assertFalse(result.skipped)
        assertEquals("2.0.0", Files.readString(targetDir.resolve(".kast-copilot-version")).trim())
        assertFalse(Files.exists(targetDir.resolve("stale.txt")))
    }

    @Test
    fun installFailsWithoutForceWhenADifferentVersionIsAlreadyInstalled() {
        val targetDir = tempDir.resolve(".github")
        val initialService = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.0.0"),
        )
        val updatedService = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "2.0.0"),
        )

        initialService.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = false))

        val failure = assertThrows<CliFailure> {
            updatedService.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = false))
        }

        assertEquals("INSTALL_COPILOT_EXTENSION_ERROR", failure.code)
        assertTrue(failure.message.contains("--yes=true"))
    }

    @Test
    fun defaultTargetDirIsGithubDirectoryUnderCurrentWorkingDirectory() {
        val cwd = tempDir.resolve("workspace")
        Files.createDirectories(cwd)
        val service = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.2.3"),
            cwdProvider = { cwd },
        )

        val result = service.install(InstallCopilotExtensionOptions(targetDir = null, force = false))

        assertEquals(cwd.resolve(".github").toAbsolutePath().normalize().toString(), result.installedAt)
        assertTrue(Files.isRegularFile(cwd.resolve(".github/.kast-copilot-version")))
    }
}
