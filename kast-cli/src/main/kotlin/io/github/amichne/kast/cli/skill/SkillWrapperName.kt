package io.github.amichne.kast.cli.skill

/**
 * Identifies each skill wrapper command by its CLI name.
 * The [cliName] matches the positional argument after `kast skill`.
 */
internal enum class SkillWrapperName(val cliName: String) {
    RESOLVE("resolve"),
    REFERENCES("references"),
    CALLERS("callers"),
    DIAGNOSTICS("diagnostics"),
    RENAME("rename"),
    SCAFFOLD("scaffold"),
    WRITE_AND_VALIDATE("write-and-validate"),
    WORKSPACE_FILES("workspace-files"),
    METRICS("metrics"),
    ;

    companion object {
        private val byCliName = entries.associateBy { it.cliName }

        fun fromCliName(name: String): SkillWrapperName? = byCliName[name]
    }
}
