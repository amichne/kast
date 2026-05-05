package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.currentCliVersion
import java.io.InputStream
import java.nio.file.Path

internal class EmbeddedCopilotExtensionResources(
    version: String = currentCliVersion(),
    resourceReader: (String) -> InputStream? = { relativePath ->
        EmbeddedCopilotExtensionResources::class.java.getResourceAsStream("/$RESOURCE_ROOT/$relativePath")
    },
) : EmbeddedResourceBundle(
    version = version,
    resourceRoot = RESOURCE_ROOT,
    manifest = MANIFEST,
    versionMarkerFileName = VERSION_MARKER_FILE_NAME,
    resourceReader = resourceReader,
    missingResourceErrorCode = "INSTALL_COPILOT_EXTENSION_ERROR",
    resourceDescription = "kast Copilot extension",
) {
    fun writeCopilotExtensionTree(targetDir: Path) = writeTree(targetDir)

    companion object {
        const val RESOURCE_ROOT: String = "packaged-copilot-extension"
        const val VERSION_MARKER_FILE_NAME: String = ".kast-copilot-version"

        val MANIFEST: List<String> = listOf(
            "agents/kast.md",
            "agents/explore.md",
            "agents/plan.md",
            "agents/edit.md",
            "hooks/hooks.json",
            "hooks/hook-state.sh",
            "hooks/session-start.sh",
            "hooks/record-paths.sh",
            "hooks/require-skills.sh",
            "hooks/session-end.sh",
            "hooks/resolve-kast-cli-path.sh",
            "extensions/_shared/shadowed-skill-state.mjs",
            "extensions/kast/extension.mjs",
            "extensions/kast/scripts/resolve-kast.sh",
            "extensions/kotlin-gradle-loop/extension.mjs",
            "extensions/kotlin-gradle-loop/scripts/gradle/run_gradle_hook.sh",
            "extensions/kotlin-gradle-loop/scripts/gradle/run_task.sh",
            "extensions/kotlin-gradle-loop/scripts/parse/jacoco_report.py",
            "extensions/kotlin-gradle-loop/scripts/parse/junit_results.py",
            "extensions/kotlin-gradle-loop/scripts/parse/kotlin_build_report.py",
            "extensions/kotlin-gradle-loop/scripts/state/get_state.py",
            "extensions/kotlin-gradle-loop/scripts/state/init_state.py",
            "extensions/kotlin-gradle-loop/scripts/state/record_action.py",
            "extensions/kotlin-gradle-loop/scripts/state/update_state.py",
        )
    }
}
