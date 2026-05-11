package io.github.amichne.kast.api.client.fields

data class ProfilingDurationSeconds(
    override val value: Long,
) : ConfigurationField<Long>() {
    override val section: String get() = "profiling"
    override val key: String get() = "durationSeconds"
    override val default: ConfigurationDefault<Long> get() = ConfigurationDefault(30L)
}
