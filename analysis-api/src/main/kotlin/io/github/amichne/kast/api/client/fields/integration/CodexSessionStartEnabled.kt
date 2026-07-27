package io.github.amichne.kast.api.client.fields

data class CodexSessionStartEnabled(
    override val value: Boolean,
) : ConfigurationField<Boolean>() {
    override val section: String get() = "codex.hooks"
    override val key: String get() = "sessionStart"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(true)
}
