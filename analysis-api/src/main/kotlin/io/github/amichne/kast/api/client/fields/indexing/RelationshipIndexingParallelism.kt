package io.github.amichne.kast.api.client.fields

data class RelationshipIndexingParallelism(
    override val value: Int,
) : ConfigurationField<Int>() {
    override val section: String get() = "indexing.relationships"
    override val key: String get() = "parallelism"
    override val default: ConfigurationDefault<Int> get() = ConfigurationDefault(4)
}
