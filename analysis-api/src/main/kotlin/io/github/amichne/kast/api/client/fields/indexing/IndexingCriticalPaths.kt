package io.github.amichne.kast.api.client.fields

data class IndexingCriticalPaths(
    override val value: List<WorkspaceIndexingPattern>,
) : ConfigurationField<List<WorkspaceIndexingPattern>>() {
    companion object {
        fun parse(patterns: List<String>): IndexingCriticalPaths =
            IndexingCriticalPaths(patterns.map(WorkspaceIndexingPattern::parse))
    }

    override val section: String get() = "indexing"
    override val key: String get() = "criticalPaths"
    override val default: ConfigurationDefault<List<WorkspaceIndexingPattern>> get() = ConfigurationDefault(emptyList())
}
