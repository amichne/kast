package io.github.amichne.kast.api.client.fields

data class PathsBinDir(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "paths"
    override val key: String get() = "binDir"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault(defaultConfigBinDir().toString())
}
