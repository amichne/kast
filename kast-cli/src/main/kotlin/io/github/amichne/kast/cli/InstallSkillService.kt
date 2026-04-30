package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.options.InstallSkillOptions
import io.github.amichne.kast.cli.skill.InstallSkillResult
import io.github.amichne.kast.cli.tty.CliFailure
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class InstallSkillService(
    private val embeddedSkillResources: EmbeddedSkillResources = EmbeddedSkillResources(),
) {
    fun install(options: InstallSkillOptions): InstallSkillResult {
        val targetDir = (options.targetDir ?: resolveDefaultTargetDir(Path.of(System.getProperty("user.dir", ".")))).toAbsolutePath().normalize()
        validateName(options.name)

        Files.createDirectories(targetDir)
        val targetPath = targetDir.resolve(options.name)
        val currentVersion = embeddedSkillResources.version

        when {
            Files.isSymbolicLink(targetPath) -> {
                if (!options.force) {
                    throw existingTargetFailure(targetPath)
                }
                deletePathRecursively(targetPath)
            }

            Files.isDirectory(targetPath) -> {
                val existingVersion = readInstalledVersion(targetPath)
                if (existingVersion == currentVersion) {
                    return InstallSkillResult(
                        installedAt = targetPath.toString(),
                        version = currentVersion,
                        skipped = true,
                    )
                }
                if (!options.force) {
                    throw existingTargetFailure(targetPath)
                }
                deletePathRecursively(targetPath)
            }

            Files.exists(targetPath) -> {
                if (!options.force) {
                    throw existingTargetFailure(targetPath)
                }
                deletePathRecursively(targetPath)
            }
        }

        embeddedSkillResources.writeSkillTree(targetPath)
        return InstallSkillResult(
            installedAt = targetPath.toString(),
            version = currentVersion,
            skipped = false,
        )
    }

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

    private fun readInstalledVersion(targetPath: Path): String? {
        val markerPath = targetPath.resolve(EmbeddedSkillResources.VERSION_MARKER_FILE_NAME)
        if (!Files.isRegularFile(markerPath)) {
            return null
        }
        return Files.readString(markerPath)
            .trim()
            .takeIf(String::isNotEmpty)
    }

    private fun existingTargetFailure(targetPath: Path): CliFailure {
        return CliFailure(
            code = "INSTALL_SKILL_ERROR",
            message = "Packaged kast skill already exists at $targetPath; rerun with --yes=true to overwrite it",
        )
    }

    private fun deletePathRecursively(path: Path) {
        if (Files.isSymbolicLink(path) || Files.isRegularFile(path)) {
            Files.deleteIfExists(path)
            return
        }

        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    throw exc
                }
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
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
