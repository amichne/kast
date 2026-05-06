package io.github.amichne.kast.api.client.fields

data class TelemetryOutputFile(
    override val value: OptionalConfigString,
) : ConfigurationField<OptionalConfigString>() {
    override val section: String get() = "telemetry"
    override val key: String get() = "outputFile"
    override val default: ConfigurationDefault<OptionalConfigString> get() = ConfigurationDefault(OptionalConfigString.Unset)
}
