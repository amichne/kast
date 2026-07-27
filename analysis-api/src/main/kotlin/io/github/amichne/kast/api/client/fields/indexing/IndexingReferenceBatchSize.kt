package io.github.amichne.kast.api.client.fields

data class IndexingReferenceBatchSize(
    override val value: Int,
) : ConfigurationField<Int>() {
    override val section: String get() = "indexing"
    override val key: String get() = "referenceBatchSize"
    override val default: ConfigurationDefault<Int> get() = ConfigurationDefault(50)
}
