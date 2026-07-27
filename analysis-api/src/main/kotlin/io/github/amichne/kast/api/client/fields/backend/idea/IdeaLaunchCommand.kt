package io.github.amichne.kast.api.client.fields

data class IdeaLaunchCommand(
    override val value: String,
) : ConfigurationField<String>() {
    init {
        require(value.isNotBlank()) { "runtime.ideaLaunch.command must not be blank" }
    }

    override val section: String get() = "runtime.ideaLaunch"
    override val key: String get() = "command"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault("idea")
}
