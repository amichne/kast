package io.github.amichne.kast.api.client.fields

data class StandaloneRuntimeLibsDir(
    override val value: OptionalConfigString,
) : ConfigurationField<OptionalConfigString>() {
    override val section: String get() = "backends.standalone"
    override val key: String get() = "runtimeLibsDir"
    override val default: ConfigurationDefault<OptionalConfigString> get() = ConfigurationDefault(OptionalConfigString.Unset)
}
