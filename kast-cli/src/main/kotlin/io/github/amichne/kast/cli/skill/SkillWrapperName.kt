package io.github.amichne.kast.cli.skill

/**
 * Identifies each skill wrapper command by its CLI name and the corresponding
 * native Copilot tool name exposed by the repo-local extension.
 */
internal enum class SkillWrapperName(val cliName: String, val nativeToolName: String) {
    RESOLVE("resolve", "kast_resolve"),
    REFERENCES("references", "kast_references"),
    CALLERS("callers", "kast_callers"),
    DIAGNOSTICS("diagnostics", "kast_diagnostics"),
    RENAME("rename", "kast_rename"),
    SCAFFOLD("scaffold", "kast_scaffold"),
    WRITE_AND_VALIDATE("write-and-validate", "kast_write_and_validate"),
    WORKSPACE_FILES("workspace-files", "kast_workspace_files"),
    METRICS("metrics", "kast_metrics"),
    ;

    companion object {
        private val byCliName = entries.associateBy { it.cliName }

        fun fromCliName(name: String): SkillWrapperName? = byCliName[name]
    }
}
