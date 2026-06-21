package io.github.amichne.kast.api.client.fields

data class PathsRuntimeDir(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "paths"
    override val key: String get() = "runtimeDir"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault(defaultConfigRuntimeDir().toString())
}
