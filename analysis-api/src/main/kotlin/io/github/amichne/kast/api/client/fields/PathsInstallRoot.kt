package io.github.amichne.kast.api.client.fields

data class PathsInstallRoot(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "paths"
    override val key: String get() = "installRoot"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault(defaultConfigInstallRoot().toString())
}
