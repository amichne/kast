package io.github.amichne.kast.api.client.fields

data class ProfilingOutputDir(
    override val value: String,
) : ConfigurationField<String>() {
    override val section: String get() = "profiling"
    override val key: String get() = "outputDir"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault("{logsDir}/profiling")
}
