package io.github.amichne.kast.api.client.fields

data class CacheEnabled(
    override val value: Boolean,
) : ConfigurationField<Boolean>() {
    override val section: String get() = "cache"
    override val key: String get() = "enabled"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(true)
}
