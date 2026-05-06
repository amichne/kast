package io.github.amichne.kast.api.client.fields

data class TelemetryDetail(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "telemetry"
    override val key: String get() = "detail"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault("basic")
}
