package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.currentCliVersion
import java.io.InputStream
import java.nio.file.Path

internal class EmbeddedSkillResources(
    version: String = currentCliVersion(),
    resourceReader: (String) -> InputStream? = { relativePath ->
        EmbeddedSkillResources::class.java.getResourceAsStream("/$RESOURCE_ROOT/$relativePath")
    },
) : EmbeddedResourceBundle(
    version = version,
    resourceRoot = RESOURCE_ROOT,
    manifest = MANIFEST,
    versionMarkerFileName = VERSION_MARKER_FILE_NAME,
    resourceReader = resourceReader,
    missingResourceErrorCode = "INSTALL_SKILL_ERROR",
    resourceDescription = "kast skill",
) {
    fun writeSkillTree(targetDir: Path) = writeTree(targetDir)

    companion object {
        const val RESOURCE_ROOT: String = "packaged-skill"
        const val VERSION_MARKER_FILE_NAME: String = ".kast-version"

        val MANIFEST: List<String> = listOf(
            "SKILL.md",
            "evals/catalog.json",
            "evals/pain_points.jsonl",
            "evals/files/.gitkeep",
            "history/progression.json",
            "history/eval-baseline.json",
            "references/routing-improvement.md",
            "references/wrapper-openapi.yaml",
            "scripts/build-routing-corpus.py",
            "references/quickstart.md",
            "scripts/kast-session-start.sh",
            "scripts/resolve-kast.sh",
        )
    }
}
