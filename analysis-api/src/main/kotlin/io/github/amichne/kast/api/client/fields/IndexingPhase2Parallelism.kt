package io.github.amichne.kast.api.client.fields

data class IndexingPhase2Parallelism(
    override val value: Int,
) : ConfigurationField<Int>() {
    override val section: String get() = "indexing"
    override val key: String get() = "phase2Parallelism"
    override val default: ConfigurationDefault<Int> get() = ConfigurationDefault(2)
}
