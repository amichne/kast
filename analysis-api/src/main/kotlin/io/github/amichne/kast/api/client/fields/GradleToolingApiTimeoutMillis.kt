package io.github.amichne.kast.api.client.fields

data class GradleToolingApiTimeoutMillis(
    override val value: Long,
) : ConfigurationField<Long>() {
    override val section: String get() = "gradle"
    override val key: String get() = "toolingApiTimeoutMillis"
    override val default: ConfigurationDefault<Long> get() = ConfigurationDefault(60_000L)
}
