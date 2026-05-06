package io.github.amichne.kast.api.client.fields

data class IndexingPhase2Enabled(
    override val value: Boolean,
) : ConfigurationField<Boolean>() {
    override val section: String get() = "indexing"
    override val key: String get() = "phase2Enabled"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(true)
}
