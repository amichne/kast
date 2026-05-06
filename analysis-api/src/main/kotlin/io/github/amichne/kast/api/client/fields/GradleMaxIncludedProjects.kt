package io.github.amichne.kast.api.client.fields

data class GradleMaxIncludedProjects(
    override val value: Int,
) : ConfigurationField<Int>() {
    override val section: String get() = "gradle"
    override val key: String get() = "maxIncludedProjects"
    override val default: ConfigurationDefault<Int> get() = ConfigurationDefault(200)
}
