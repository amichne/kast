package io.github.amichne.kast.api.client.fields

data class PathsSocketDir(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "paths"
    override val key: String get() = "socketDir"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault(defaultConfigSocketDir())
}
