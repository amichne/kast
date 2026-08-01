package io.github.amichne.kast.api.client.fields

data class IndexingCriticalPaths(
    override val value: List<String>,
) : ConfigurationField<List<String>>() {
    init {
        require(value.all(String::isNotBlank)) { "indexing.criticalPaths must not contain blank patterns" }
    }

    override val section: String get() = "indexing"
    override val key: String get() = "criticalPaths"
    override val default: ConfigurationDefault<List<String>> get() = ConfigurationDefault(emptyList())
}
