package io.github.amichne.kast.api.client.fields

data class CliBinaryPath(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "cli"
    override val key: String get() = "binaryPath"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault(defaultConfigCliBinaryPath().toString())
}
