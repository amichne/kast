package io.github.amichne.kast.api.client.fields

data class TelemetryScopes(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "telemetry"
    override val key: String get() = "scopes"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault("all")
}
