package io.github.amichne.kast.api.client.fields

data class IdeaLaunchRequireInstalledPlugin(
    override val value: Boolean,
) : ConfigurationField<Boolean>() {
    override val section: String get() = "runtime.ideaLaunch"
    override val key: String get() = "requireInstalledPlugin"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(true)
}
