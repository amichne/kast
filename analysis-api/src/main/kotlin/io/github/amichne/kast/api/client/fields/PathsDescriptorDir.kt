package io.github.amichne.kast.api.client.fields

data class PathsDescriptorDir(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "paths"
    override val key: String get() = "descriptorDir"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault(defaultConfigDescriptorDir().toString())
}
