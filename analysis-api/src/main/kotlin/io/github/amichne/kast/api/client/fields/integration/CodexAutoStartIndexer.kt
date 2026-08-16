package io.github.amichne.kast.api.client.fields

data class CodexAutoStartIndexer(
    override val value: IndexerAutoStartConsent,
) : ConfigurationField<IndexerAutoStartConsent>() {
    override val section: String get() = "codex.hooks"
    override val key: String get() = "autoStartIndexer"
    override val default: ConfigurationDefault<IndexerAutoStartConsent>
        get() = ConfigurationDefault(IndexerAutoStartConsent.Unconfigured)
}
