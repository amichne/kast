package io.github.amichne.kast.api.client.fields

import java.nio.file.Path

data class PathsDescriptorDir(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "paths"
    override val key: String get() = "descriptorDir"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault(defaultConfigDescriptorDir().toString())

    fun toPath(): Path = Path.of(value).toAbsolutePath().normalize()
}
