package io.github.amichne.kast.api.client.fields

data class IndexingIgnoredPaths(
    override val value: List<String>,
) : ConfigurationField<List<String>>() {
    init {
        require(value.all(String::isNotBlank)) { "indexing.ignoredPaths must not contain blank patterns" }
    }

    override val section: String get() = "indexing"
    override val key: String get() = "ignoredPaths"
    override val default: ConfigurationDefault<List<String>> get() = ConfigurationDefault(emptyList())
}
