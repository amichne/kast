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
    resourceDescription = "kast packaged skill",
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
            "fixtures/maintenance/evals/evals.json",
            "fixtures/maintenance/evals/routing.json",
            "fixtures/maintenance/references/routing-improvement.md",
            "fixtures/maintenance/references/wrapper-openapi.yaml",
            "fixtures/maintenance/scripts/build-routing-corpus.py",
            "history/eval-baseline.json",
            "history/progression.json",
            "references/quickstart.md",
            "references/commands.json",
            "references/routing-improvement.md",
            "references/wrapper-openapi.yaml",
            "scripts/build-routing-corpus.py",
            "scripts/kast-session-start.sh",
            "scripts/resolve-kast.sh",
            "value-proof/README.md",
            "value-proof/bindings.schema.json",
            "value-proof/bindings/konditional.json",
            "value-proof/bindings/template.json",
            "value-proof/catalog.json",
            "value-proof/history/progression.json",
            "value-proof/scripts/generate_executive_summary.py",
            "value-proof/scripts/render_prompts.py",
            "value-proof/scripts/run_value_proof.py",
        )
    }
}
