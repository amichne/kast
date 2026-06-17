package io.github.amichne.kast.api.client.fields

data class ProjectOpenProfile(
    override val value: String,
) : ConfigurationField<String>() {
    init {
        require(value in allowedValues) {
            "projectOpen.profile must be one of ${allowedValues.joinToString(", ")}"
        }
    }

    override val section: String get() = "projectOpen"
    override val key: String get() = "profile"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault("copilot-lsp")

    companion object {
        const val COPILOT_LSP: String = "copilot-lsp"
        private val allowedValues = setOf(COPILOT_LSP)
    }
}
