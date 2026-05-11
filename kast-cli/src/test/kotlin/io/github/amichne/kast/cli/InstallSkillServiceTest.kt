package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.options.InstallSkillOptions
import io.github.amichne.kast.cli.tty.CliFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

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
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("evals/catalog.json")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("evals/pain_points.jsonl")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("evals/files/.gitkeep")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("fixtures/maintenance/evals/evals.json")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("fixtures/maintenance/evals/routing.json")))
        assertTrue(
            Files.isRegularFile(
                installedSkillDir.resolve("fixtures/maintenance/references/routing-improvement.md"),
            ),
        )
        assertTrue(
            Files.isRegularFile(
                installedSkillDir.resolve("fixtures/maintenance/references/wrapper-openapi.yaml"),
            ),
        )
        assertTrue(
            Files.isRegularFile(
                installedSkillDir.resolve("fixtures/maintenance/scripts/build-routing-corpus.py"),
            ),
        )
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("history/eval-baseline.json")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("history/progression.json")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("references/quickstart.md")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("references/routing-improvement.md")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("references/wrapper-openapi.yaml")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/build-routing-corpus.py")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/kast-session-start.sh")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("scripts/resolve-kast.sh")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("evaluation/catalog.json")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("evaluation/catalog.schema.json")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("evaluation/bindings/konditional.json")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("evaluation/scripts/render_prompts.py")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("evaluation/scripts/run_evaluation.py")))
        assertFalse(Files.exists(installedSkillDir.resolve("agents/openai.yaml")))
        assertFalse(Files.exists(installedSkillDir.resolve("agents/kast.md")))
        assertFalse(Files.exists(installedSkillDir.resolve("references/cloud-setup.md")))
        assertEquals("1.2.3", Files.readString(installedSkillDir.resolve(".kast-version")).trim())
        val resolveScript = Files.readString(installedSkillDir.resolve("scripts/resolve-kast.sh"))
        assertTrue(resolveScript.contains("read_config_binary_path"))
        assertTrue(resolveScript.contains("\${HOME}/.kast/bin/kast"))
        assertFalse(resolveScript.contains(".local/bin/kast"))
    }

    @Test
    fun `installed resolver script prefers config binary path before home fallback`() {
        val targetDir = tempDir.resolve("skills")
        InstallSkillService(embeddedSkillResources = EmbeddedSkillResources(version = "1.2.3")).install(
            InstallSkillOptions(
                targetDir = targetDir,
                name = "kast",
                force = false,
            ),
        )
        val installedSkillDir = targetDir.resolve("kast")
        val resolverScript = installedSkillDir.resolve("scripts/resolve-kast.sh")
        val home = tempDir.resolve("home")
        val configHome = tempDir.resolve("config-home")
        val emptyPath = tempDir.resolve("empty-path").also(Files::createDirectories)
        val configBinary = writeExecutable(tempDir.resolve("configured-kast"))
        val homeBinary = writeExecutable(home.resolve(".kast/bin/kast"))
        Files.createDirectories(configHome)
        Files.writeString(
            configHome.resolve("config.toml"),
            """
            [cli]
            binaryPath = "${configBinary.toAbsolutePath().normalize()}"
            """.trimIndent() + "\n",
        )

        val configured = runResolverScript(
            resolverScript = resolverScript,
            env = mapOf(
                "HOME" to home.toString(),
                "KAST_CONFIG_HOME" to configHome.toString(),
                "PATH" to listOf("/usr/bin", "/bin", emptyPath.toString()).joinToString(":"),
            ),
        )
        assertEquals(0, configured.exitCode, configured.stderr)
        assertEquals(configBinary.toAbsolutePath().normalize().toString(), configured.stdout)

        Files.delete(configHome.resolve("config.toml"))
        val fallback = runResolverScript(
            resolverScript = resolverScript,
            env = mapOf(
                "HOME" to home.toString(),
                "KAST_CONFIG_HOME" to configHome.toString(),
                "PATH" to listOf("/usr/bin", "/bin", emptyPath.toString()).joinToString(":"),
            ),
        )
        assertEquals(0, fallback.exitCode, fallback.stderr)
        assertEquals(homeBinary.toAbsolutePath().normalize().toString(), fallback.stdout)
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
    fun `install prefers agents skills when multiple workspace skill directories exist`() {
        Files.createDirectories(tempDir.resolve(".agents/skills"))
        Files.createDirectories(tempDir.resolve(".github/skills"))
        Files.createDirectories(tempDir.resolve(".claude/skills"))
        val service = InstallSkillService(
            embeddedSkillResources = EmbeddedSkillResources(version = "1.2.3"),
            cwdProvider = { tempDir },
            homeDirectoryProvider = { tempDir.resolve("home") },
        )

        val result = service.install(
            InstallSkillOptions(
                targetDir = null,
                name = "kast",
                force = false,
            ),
        )

        assertEquals(tempDir.resolve(".agents/skills/kast").toString(), result.installedAt)
        assertTrue(Files.isDirectory(tempDir.resolve(".agents/skills/kast")))
        assertFalse(Files.exists(tempDir.resolve(".github/skills/kast")))
        assertFalse(Files.exists(tempDir.resolve(".claude/skills/kast")))
    }

    @Test
    fun `install prefers github skills before claude when agents skills are absent`() {
        Files.createDirectories(tempDir.resolve(".github/skills"))
        Files.createDirectories(tempDir.resolve(".claude/skills"))
        val service = InstallSkillService(
            embeddedSkillResources = EmbeddedSkillResources(version = "1.2.3"),
            cwdProvider = { tempDir },
            homeDirectoryProvider = { tempDir.resolve("home") },
        )

        val result = service.install(
            InstallSkillOptions(
                targetDir = null,
                name = "kast",
                force = false,
            ),
        )

        assertEquals(tempDir.resolve(".github/skills/kast").toString(), result.installedAt)
        assertTrue(Files.isDirectory(tempDir.resolve(".github/skills/kast")))
        assertFalse(Files.exists(tempDir.resolve(".claude/skills/kast")))
    }

    @Test
    fun `install falls back to global kast lib skills when no workspace skill directory exists`() {
        val home = tempDir.resolve("home")
        val service = InstallSkillService(
            embeddedSkillResources = EmbeddedSkillResources(version = "1.2.3"),
            cwdProvider = { tempDir },
            homeDirectoryProvider = { home },
        )

        val result = service.install(
            InstallSkillOptions(
                targetDir = null,
                name = "kast",
                force = false,
            ),
        )

        assertEquals(home.resolve(".kast/lib/skills/kast").toString(), result.installedAt)
        assertTrue(Files.isDirectory(home.resolve(".kast/lib/skills/kast")))
    }

    @Test
    fun `install preserves unrelated files when forced`() {
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
        assertTrue(Files.exists(targetPath.resolve("stale.txt")))
        assertEquals("old", Files.readString(targetPath.resolve("stale.txt")))
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

    private fun writeExecutable(path: Path): Path {
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            """
            #!/usr/bin/env bash
            exit 0
            """.trimIndent() + "\n",
        )
        path.toFile().setExecutable(true)
        return path
    }

    private fun runResolverScript(
        resolverScript: Path,
        env: Map<String, String>,
    ): CommandResult {
        val process = ProcessBuilder("/bin/bash", resolverScript.toString())
            .directory(tempDir.toFile())
            .apply {
                environment().clear()
                environment().putAll(env)
            }
            .start()
        process.waitFor(10, TimeUnit.SECONDS)
        return CommandResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText().trim(),
            stderr = process.errorStream.bufferedReader().readText().trim(),
        )
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
