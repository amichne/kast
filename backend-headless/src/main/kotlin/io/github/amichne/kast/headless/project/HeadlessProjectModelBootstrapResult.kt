package io.github.amichne.kast.headless

sealed class HeadlessProjectModelBootstrapResult {
    data class Skipped(val reason: String) : HeadlessProjectModelBootstrapResult()
    data class Ready(
        val moduleNames: List<String>,
        val linkedGradleProject: Boolean,
    ) : HeadlessProjectModelBootstrapResult()
}
