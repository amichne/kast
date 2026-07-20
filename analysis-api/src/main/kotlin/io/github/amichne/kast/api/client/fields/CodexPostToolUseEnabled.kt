package io.github.amichne.kast.api.client.fields

data class CodexPostToolUseEnabled(
    override val value: Boolean,
) : ConfigurationField<Boolean>() {
    override val section: String get() = "codex.hooks"
    override val key: String get() = "postToolUse"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(true)
}
