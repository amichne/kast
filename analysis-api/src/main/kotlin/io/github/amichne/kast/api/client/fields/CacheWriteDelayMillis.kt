package io.github.amichne.kast.api.client.fields

data class CacheWriteDelayMillis(
    override val value: Long,
) : ConfigurationField<Long>() {
    override val section: String get() = "cache"
    override val key: String get() = "writeDelayMillis"
    override val default: ConfigurationDefault<Long> get() = ConfigurationDefault(5_000L)
}
