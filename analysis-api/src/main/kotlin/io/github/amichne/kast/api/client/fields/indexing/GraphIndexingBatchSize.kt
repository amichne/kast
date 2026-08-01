package io.github.amichne.kast.api.client.fields

data class GraphIndexingBatchSize(
    override val value: Int,
) : ConfigurationField<Int>() {
    init {
        require(value > 0) { "indexing.graph.batchSize must be greater than zero" }
    }

    override val section: String get() = "indexing.graph"
    override val key: String get() = "batchSize"
    override val default: ConfigurationDefault<Int> get() = ConfigurationDefault(32)
}
