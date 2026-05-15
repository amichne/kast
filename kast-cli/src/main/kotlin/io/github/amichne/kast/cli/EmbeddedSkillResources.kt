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
            "evals/pain_points.jsonl",
            "evals/files/.gitkeep",
            "fixtures/maintenance/references/routing-improvement.md",
            "fixtures/maintenance/scripts/build-routing-corpus.py",
            "references/quickstart.md",
            "references/commands.json",
            "references/routing-improvement.md",
            "scripts/build-routing-corpus.py",
            "scripts/kast-session-start.sh",
            "scripts/resolve-kast.sh",
            "evaluation/README.md",
            "evaluation/bindings.schema.json",
            "evaluation/bindings/konditional.json",
            "evaluation/bindings/template.json",
            "evaluation/catalog.json",
            "evaluation/catalog.schema.json",
            "evaluation/fixtures/.gitkeep",
            "evaluation/grading.schema.json",
            "evaluation/scripts/dispatch_runs.py",
            "evaluation/scripts/finalize_grading.py",
            "evaluation/scripts/generate_executive_summary.py",
            "evaluation/scripts/parse_tool_calls.py",
            "evaluation/scripts/render_prompts.py",
            "evaluation/scripts/run_evaluation.py",
            "evaluation/scripts/run_value_proof.py",
            "evaluation/scripts/value_proof_aggregate.py",
        )
    }
}
