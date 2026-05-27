package io.github.amichne.kast.api.client.fields

enum class GradleDiscoveryMode {
    CONSTRAINED,
    COMPLETE,
    ;

    companion object {
        fun parse(value: String): GradleDiscoveryMode = when (value.trim().lowercase()) {
            "constrained" -> CONSTRAINED
            "complete" -> COMPLETE
            else -> error("Invalid gradle.discoveryMode value: $value")
        }
    }
}

data class GradleDiscoveryModeField(
    override val value: GradleDiscoveryMode,
) : ConfigurationField<GradleDiscoveryMode>() {
    override val section: String get() = "gradle"
    override val key: String get() = "discoveryMode"
    override val default: ConfigurationDefault<GradleDiscoveryMode> get() = ConfigurationDefault(GradleDiscoveryMode.CONSTRAINED)
}
