package io.github.amichne.kast.api.client.fields

data class HeadlessIdeaHome(
    override val value: OptionalConfigString,
) : ConfigurationField<OptionalConfigString>() {
    override val section: String get() = "backends.headless"
    override val key: String get() = "ideaHome"
    override val default: ConfigurationDefault<OptionalConfigString> get() = ConfigurationDefault(OptionalConfigString.Unset)
}
