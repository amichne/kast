package io.github.amichne.kast.cli

import java.nio.file.Files
import java.nio.file.Path

internal class InstallSkillService {
    fun install(options: InstallSkillOptions): InstallSkillResult {
        val skillPath = resolvePackagedSkillPath()
        val targetDir = (options.targetDir ?: resolveDefaultTargetDir(Path.of(System.getProperty("user.dir", ".")))).toAbsolutePath().normalize()

        Files.createDirectories(targetDir)
        val targetPath = targetDir.resolve(options.linkName)

        if (Files.isSymbolicLink(targetPath)) {
            val existing = Files.readSymbolicLink(targetPath)
            val resolvedExisting = targetPath.parent.resolve(existing).normalize()
            if (resolvedExisting == skillPath) {
                return InstallSkillResult(
                    linkedFrom = targetPath.toString(),
                    linkedTo = skillPath.toString(),
                    skipped = true,
                )
            }
            if (!options.force) {
                throw CliFailure(
                    code = "INSTALL_SKILL_ERROR",
                    message = "Symlink already exists at $targetPath pointing to $existing; rerun with --yes=true to replace it",
                )
            }
            Files.delete(targetPath)
        } else if (Files.exists(targetPath)) {
            throw CliFailure(
                code = "INSTALL_SKILL_ERROR",
                message = "Target already exists and is not a symlink: $targetPath",
            )
        }

        Files.createSymbolicLink(targetPath, skillPath)
        return InstallSkillResult(
            linkedFrom = targetPath.toString(),
            linkedTo = skillPath.toString(),
            skipped = false,
        )
    }

    private fun resolvePackagedSkillPath(): Path {
        val override = System.getenv("KAST_SKILL_PATH")
        if (!override.isNullOrBlank()) {
            val path = Path.of(override).toAbsolutePath().normalize()
            if (!Files.isDirectory(path)) {
                throw CliFailure(
                    code = "INSTALL_SKILL_ERROR",
                    message = "KAST_SKILL_PATH directory not found: $path",
                )
            }
            return path
        }

        val installRoot = resolveInstallRoot()
            ?: throw CliFailure(
                code = "INSTALL_SKILL_ERROR",
                message = "Could not resolve Kast installation root; set KAST_SKILL_PATH to the packaged skill directory",
            )
        val skillPath = installRoot.resolve("share/skills/kast").normalize()
        if (!Files.isDirectory(skillPath)) {
            throw CliFailure(
                code = "INSTALL_SKILL_ERROR",
                message = "Packaged kast skill not found at $skillPath; set KAST_SKILL_PATH to override",
            )
        }
        return skillPath
    }

    private fun resolveInstallRoot(): Path? {
        // The main jar lives in ${install_root}/runtime-libs/, so its grandparent is the install root.
        val codeSource = InstallSkillService::class.java.protectionDomain?.codeSource?.location
            ?: return null
        val jarPath = runCatching { Path.of(codeSource.toURI()) }.getOrNull()
            ?: return null
        if (!Files.isRegularFile(jarPath)) return null
        // runtime-libs/ -> install root
        val candidate = jarPath.parent?.parent?.normalize() ?: return null
        return candidate.takeIf { Files.isDirectory(it) }
    }

    private fun resolveDefaultTargetDir(cwd: Path): Path {
        val agentsSkill = cwd.resolve(".agents/skill")
        if (Files.isDirectory(agentsSkill)) return agentsSkill

        val agentsSkills = cwd.resolve(".agents/skills")
        if (Files.isDirectory(agentsSkills) || Files.isDirectory(cwd.resolve(".agents"))) return agentsSkills

        val githubSkills = cwd.resolve(".github/skills")
        if (Files.isDirectory(githubSkills) || Files.isDirectory(cwd.resolve(".github"))) return githubSkills

        val claudeSkills = cwd.resolve(".claude/skills")
        if (Files.isDirectory(claudeSkills) || Files.isDirectory(cwd.resolve(".claude"))) return claudeSkills

        return agentsSkills
    }
}
