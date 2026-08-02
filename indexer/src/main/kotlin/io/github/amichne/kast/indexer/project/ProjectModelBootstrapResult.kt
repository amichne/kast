package io.github.amichne.kast.indexer.project

sealed class ProjectModelBootstrapResult {
    data class Skipped(val reason: String) : ProjectModelBootstrapResult()
    data class Ready(
        val moduleNames: List<String>,
        val linkedGradleProject: Boolean,
    ) : ProjectModelBootstrapResult()
}
