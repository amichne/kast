package io.github.amichne.kast.api.client.fields

data class PathsCacheDir(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "paths"
    override val key: String get() = "cacheDir"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault(defaultConfigCacheDir().toString())
}
