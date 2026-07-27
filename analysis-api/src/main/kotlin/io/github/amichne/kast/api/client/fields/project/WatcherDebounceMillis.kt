package io.github.amichne.kast.api.client.fields

data class WatcherDebounceMillis(
    override val value: Long,
) : ConfigurationField<Long>() {
    override val section: String get() = "watcher"
    override val key: String get() = "debounceMillis"
    override val default: ConfigurationDefault<Long> get() = ConfigurationDefault(200L)
}
