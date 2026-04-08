package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstallSkillServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `install copies bundled skill tree and writes version marker`() {
        val targetDir = tempDir.resolve("skills")
        val service = InstallSkillService(embeddedSkillResources = EmbeddedSkillResources(version = "1.2.3"))

        val result = service.install(
            InstallSkillOptions(
                targetDir = targetDir,
                name = "kast",
                force = false,
            ),
        )

        val installedSkillDir = targetDir.resolve("kast")
        assertEquals(installedSkillDir.toString(), result.installedAt)
        assertEquals("1.2.3", result.version)
        assertFalse(result.skipped)
        assertTrue(Files.isDirectory(installedSkillDir))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("SKILL.md")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("agents/openai.yaml")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/find-symbol-offset.py")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/kast-callers.sh")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/kast-common.sh")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/kast-diagnostics.sh")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/kast-impact.sh")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/kast-plan-utils.py")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/kast-references.sh")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/kast-resolve.sh")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/resolve-kast.sh")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/validate-wrapper-json.sh")))
        assertEquals("1.2.3", Files.readString(installedSkillDir.resolve(".kast-version")).trim())
    }

    @Test
    fun `install skips when the same version is already installed`() {
        val targetDir = tempDir.resolve("skills")
        val service = InstallSkillService(embeddedSkillResources = EmbeddedSkillResources(version = "1.2.3"))
        val options = InstallSkillOptions(
            targetDir = targetDir,
            name = "kast",
            force = false,
        )

        service.install(options)
        val result = service.install(options)

        assertTrue(result.skipped)
        assertEquals("1.2.3", result.version)
    }

    @Test
    fun `install overwrites an existing skill directory when forced`() {
        val targetDir = tempDir.resolve("skills")
        val initialService = InstallSkillService(embeddedSkillResources = EmbeddedSkillResources(version = "1.0.0"))
        val updatedService = InstallSkillService(embeddedSkillResources = EmbeddedSkillResources(version = "2.0.0"))
        val targetPath = targetDir.resolve("kast")

        initialService.install(
            InstallSkillOptions(
                targetDir = targetDir,
                name = "kast",
                force = false,
            ),
        )
        Files.writeString(targetPath.resolve("stale.txt"), "old")

        val result = updatedService.install(
            InstallSkillOptions(
                targetDir = targetDir,
                name = "kast",
                force = true,
            ),
        )

        assertFalse(result.skipped)
        assertEquals("2.0.0", Files.readString(targetPath.resolve(".kast-version")).trim())
        assertFalse(Files.exists(targetPath.resolve("stale.txt")))
    }

    @Test
    fun `install fails without force when a different version is already installed`() {
        val targetDir = tempDir.resolve("skills")
        val initialService = InstallSkillService(embeddedSkillResources = EmbeddedSkillResources(version = "1.0.0"))
        val updatedService = InstallSkillService(embeddedSkillResources = EmbeddedSkillResources(version = "2.0.0"))

        initialService.install(
            InstallSkillOptions(
                targetDir = targetDir,
                name = "kast",
                force = false,
            ),
        )

        val failure = assertThrows<CliFailure> {
            updatedService.install(
                InstallSkillOptions(
                    targetDir = targetDir,
                    name = "kast",
                    force = false,
                ),
            )
        }

        assertEquals("INSTALL_SKILL_ERROR", failure.code)
        assertTrue(failure.message.contains("--yes=true"))
    }

    @Test
    fun `install rejects invalid skill names`() {
        val service = InstallSkillService(embeddedSkillResources = EmbeddedSkillResources(version = "1.2.3"))

        val failure = assertThrows<CliFailure> {
            service.install(
                InstallSkillOptions(
                    targetDir = tempDir.resolve("skills"),
                    name = "../kast",
                    force = false,
                ),
            )
        }

        assertEquals("INSTALL_SKILL_ERROR", failure.code)
        assertTrue(failure.message.contains("Skill name"))
    }

    @Test
    fun `install rejects dot as skill name`() {
        val service = InstallSkillService(embeddedSkillResources = EmbeddedSkillResources(version = "1.2.3"))

        val failure = assertThrows<CliFailure> {
            service.install(
                InstallSkillOptions(
                    targetDir = tempDir.resolve("skills"),
                    name = ".",
                    force = false,
                ),
            )
        }

        assertEquals("INSTALL_SKILL_ERROR", failure.code)
    }

    @Test
    fun `install rejects dot-dot as skill name`() {
        val service = InstallSkillService(embeddedSkillResources = EmbeddedSkillResources(version = "1.2.3"))

        val failure = assertThrows<CliFailure> {
            service.install(
                InstallSkillOptions(
                    targetDir = tempDir.resolve("skills"),
                    name = "..",
                    force = true,
                ),
            )
        }

        assertEquals("INSTALL_SKILL_ERROR", failure.code)
    }
}
