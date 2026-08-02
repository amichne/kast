package io.github.amichne.kast.api.client.fields

data class IndexingIgnoredPaths(
    override val value: List<WorkspaceIndexingPattern>,
) : ConfigurationField<List<WorkspaceIndexingPattern>>() {
    companion object {
        fun parse(patterns: List<String>): IndexingIgnoredPaths =
            IndexingIgnoredPaths(patterns.map(WorkspaceIndexingPattern::parse))
    }

    override val section: String get() = "indexing"
    override val key: String get() = "ignoredPaths"
    override val default: ConfigurationDefault<List<WorkspaceIndexingPattern>> get() = ConfigurationDefault(emptyList())
}
