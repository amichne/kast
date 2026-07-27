package io.github.amichne.kast.api.client.fields

data class ProfilingEmitManifest(
    override val value: Boolean,
) : ConfigurationField<Boolean>() {
    override val section: String get() = "profiling"
    override val key: String get() = "emitManifest"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(true)
}
