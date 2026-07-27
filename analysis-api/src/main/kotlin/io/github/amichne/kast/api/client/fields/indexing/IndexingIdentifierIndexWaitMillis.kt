package io.github.amichne.kast.api.client.fields

data class IndexingIdentifierIndexWaitMillis(
    override val value: Long,
) : ConfigurationField<Long>() {
    override val section: String get() = "indexing"
    override val key: String get() = "identifierIndexWaitMillis"
    override val default: ConfigurationDefault<Long> get() = ConfigurationDefault(10_000L)
}
