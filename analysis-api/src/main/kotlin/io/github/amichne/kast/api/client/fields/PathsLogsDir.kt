package io.github.amichne.kast.api.client.fields

data class PathsLogsDir(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "paths"
    override val key: String get() = "logsDir"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault(defaultConfigLogsDir().toString())
}
