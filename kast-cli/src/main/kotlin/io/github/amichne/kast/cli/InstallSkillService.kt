package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.options.InstallSkillOptions
import io.github.amichne.kast.cli.skill.InstallSkillResult
import io.github.amichne.kast.cli.tty.CliFailure
import java.nio.file.Files
import java.nio.file.Path

internal class InstallSkillService(
    embeddedSkillResources: EmbeddedSkillResources = EmbeddedSkillResources(),
    cwdProvider: () -> Path = { Path.of(System.getProperty("user.dir", ".")) },
) : InstallEmbeddedResourceService<InstallSkillOptions, InstallSkillResult>(
    bundle = embeddedSkillResources,
    errorCode = "INSTALL_SKILL_ERROR",
    installedDescription = "kast skill",
    cwdProvider = cwdProvider,
) {
    override fun installRequest(
        options: InstallSkillOptions,
        cwd: Path,
    ): InstallEmbeddedResourceRequest {
        val targetDir = options.targetDir ?: resolveDefaultTargetDir(cwd)
        validateName(options.name)
        return InstallEmbeddedResourceRequest(
            targetPath = targetDir.resolve(options.name),
            force = options.force,
        )
    }

    override fun result(
        installedAt: String,
        version: String,
        skipped: Boolean,
    ): InstallSkillResult = InstallSkillResult(
        installedAt = installedAt,
        version = version,
        skipped = skipped,
    )

    private fun validateName(name: String) {
        if (!name.matches(Regex("[A-Za-z0-9._-]+"))) {
            throw CliFailure(
                code = "INSTALL_SKILL_ERROR",
                message = "Skill name may contain only letters, digits, dot, underscore, and dash",
            )
        }
        if (name == "." || name == "..") {
            throw CliFailure(
                code = "INSTALL_SKILL_ERROR",
                message = "Skill name must not be '.' or '..'",
            )
        }
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
