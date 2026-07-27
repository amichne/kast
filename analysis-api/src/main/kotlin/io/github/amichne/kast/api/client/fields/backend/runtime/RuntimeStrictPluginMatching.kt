package io.github.amichne.kast.api.client.fields

data class RuntimeStrictPluginMatching(
    override val value: Boolean,
) : ConfigurationField<Boolean>() {
    override val section: String get() = "runtime"
    override val key: String get() = "strictPluginMatching"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(true)
}
