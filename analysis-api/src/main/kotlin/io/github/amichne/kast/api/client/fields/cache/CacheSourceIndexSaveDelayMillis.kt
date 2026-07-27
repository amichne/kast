package io.github.amichne.kast.api.client.fields

data class CacheSourceIndexSaveDelayMillis(
    override val value: Long,
) : ConfigurationField<Long>() {
    override val section: String get() = "cache"
    override val key: String get() = "sourceIndexSaveDelayMillis"
    override val default: ConfigurationDefault<Long> get() = ConfigurationDefault(5_000L)
}
