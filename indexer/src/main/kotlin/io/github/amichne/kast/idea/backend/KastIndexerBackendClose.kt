package io.github.amichne.kast.idea.backend

internal fun KastIndexerBackend.closeResources() {
    val failures = listOf(
        runCatching(referenceContinuations::close).exceptionOrNull(),
        runCatching(diagnosticContinuations::close).exceptionOrNull(),
        runCatching(relationshipContinuations::close).exceptionOrNull(),
        runCatching(workspaceFilePaging::close).exceptionOrNull(),
    ).filterNotNull()
    failures.firstOrNull()?.let { first ->
        failures.drop(1).forEach(first::addSuppressed)
        throw first
    }
}
