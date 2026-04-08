package io.github.amichne.kast.cli

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class EmbeddedSkillResources(
    val version: String = currentCliVersion(),
    private val resourceReader: (String) -> InputStream? = { relativePath ->
        EmbeddedSkillResources::class.java.getResourceAsStream("/$RESOURCE_ROOT/$relativePath")
    },
) {
    fun writeSkillTree(targetDir: Path) {
        Files.createDirectories(targetDir)
        MANIFEST.forEach { relativePath ->
            val targetPath = targetDir.resolve(relativePath)
            targetPath.parent?.let(Files::createDirectories)
            openResource(relativePath).use { input ->
                Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Files.writeString(targetDir.resolve(VERSION_MARKER_FILE_NAME), "$version${System.lineSeparator()}")
    }

    private fun openResource(relativePath: String): InputStream {
        return resourceReader(relativePath)
            ?: throw CliFailure(
                code = "INSTALL_SKILL_ERROR",
                message = "Bundled kast skill resource not found: /$RESOURCE_ROOT/$relativePath",
            )
    }

    companion object {
        const val RESOURCE_ROOT: String = "packaged-skill"
        const val VERSION_MARKER_FILE_NAME: String = ".kast-version"

        val MANIFEST: List<String> = listOf(
            "SKILL.md",
            "agents/openai.yaml",
            "references/cloud-setup.md",
            "references/command-reference.md",
            "references/troubleshooting.md",
            "scripts/find-symbol-offset.py",
            "scripts/kast-callers.sh",
            "scripts/kast-common.sh",
            "scripts/kast-diagnostics.sh",
            "scripts/kast-impact.sh",
            "scripts/kast-plan-utils.py",
            "scripts/kast-rename.sh",
            "scripts/kast-references.sh",
            "scripts/kast-resolve.sh",
            "scripts/resolve-kast.sh",
            "scripts/validate-wrapper-json.sh",
        )
    }
}
