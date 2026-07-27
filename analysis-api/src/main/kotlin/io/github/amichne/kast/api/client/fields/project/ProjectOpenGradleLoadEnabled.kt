package io.github.amichne.kast.api.client.fields

data class ProjectOpenGradleLoadEnabled(
    override val value: Boolean,
) : ConfigurationField<Boolean>() {
    override val section: String get() = "projectOpen"
    override val key: String get() = "gradleLoadEnabled"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(true)
}
