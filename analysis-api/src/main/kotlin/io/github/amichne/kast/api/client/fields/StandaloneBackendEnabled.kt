package io.github.amichne.kast.api.client.fields

data class StandaloneBackendEnabled(
    override val value: Boolean,
) : ConfigurationField<Boolean>() {
    override val section: String get() = "backends.standalone"
    override val key: String get() = "enabled"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(true)
}
