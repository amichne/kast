package io.github.amichne.kast.api.client.fields

data class ProfilingOtlpEndpoint(
    override val value: OptionalConfigString,
) : ConfigurationField<OptionalConfigString>() {
    override val section: String get() = "profiling"
    override val key: String get() = "otlpEndpoint"
    override val default: ConfigurationDefault<OptionalConfigString> get() = ConfigurationDefault(OptionalConfigString.Unset)
}
