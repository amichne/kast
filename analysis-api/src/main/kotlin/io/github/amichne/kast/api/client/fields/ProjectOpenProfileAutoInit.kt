package io.github.amichne.kast.api.client.fields

data class ProjectOpenProfileAutoInit(
    override val value: Boolean,
) : ConfigurationField<Boolean>() {
    override val section: String get() = "projectOpen"
    override val key: String get() = "profileAutoInit"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(false)
}
