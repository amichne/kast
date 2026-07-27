package io.github.amichne.kast.api.client.fields

data class ProjectOpenProfile(
    override val value: String,
) : ConfigurationField<String>() {
    init {
        require(value in allowedValues) {
            "projectOpen.profile must be one of ${allowedValues.joinToString(", ")}"
        }
    }

    val kind: ProjectOpenProfileKind = when (value) {
        JETBRAINS_PLUGIN -> ProjectOpenProfileKind.JETBRAINS_PLUGIN
        else -> error("projectOpen.profile was validated but has no semantic kind: $value")
    }

    override val section: String get() = "projectOpen"
    override val key: String get() = "profile"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault(JETBRAINS_PLUGIN)

    companion object {
        const val JETBRAINS_PLUGIN: String = "jetbrains-plugin"
        private val allowedValues = setOf(JETBRAINS_PLUGIN)
    }
}
