package io.github.amichne.kast.api.client.fields

data class ProfilingEnabled(
    override val value: Boolean,
) : ConfigurationField<Boolean>() {
    override val section: String get() = "profiling"
    override val key: String get() = "enabled"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(false)
}
