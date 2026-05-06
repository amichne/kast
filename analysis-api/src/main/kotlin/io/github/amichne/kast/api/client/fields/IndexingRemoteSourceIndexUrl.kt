package io.github.amichne.kast.api.client.fields

data class IndexingRemoteSourceIndexUrl(
    override val value: OptionalConfigString,
) : ConfigurationField<OptionalConfigString>() {
    override val section: String get() = "indexing.remote"
    override val key: String get() = "sourceIndexUrl"
    override val default: ConfigurationDefault<OptionalConfigString>
        get() = ConfigurationDefault(OptionalConfigString(defaultConfigStandaloneRuntimeLibsDir().toString()))
}
