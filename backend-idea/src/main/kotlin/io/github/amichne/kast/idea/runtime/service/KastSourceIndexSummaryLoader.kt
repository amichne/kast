package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.diagnostics.KastIndexState
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageScopeCoverage
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore

internal fun SqliteSourceIndexStore.loadKastSourceIndexSummary(
    criticalPaths: Set<String> = emptySet(),
    unmatchedCriticalPatterns: List<String> = emptyList(),
): KastSourceIndexSummary {
    val snapshot = loadSourceIndexSnapshot()
    val indexedPaths = knownSourcePaths().mapTo(linkedSetOf()) { path -> path.toString() }
    val overallCoverage = indexedPaths.takeIf(Set<String>::isNotEmpty)?.let { paths ->
        fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, paths)
    }
    val criticalCoverage = criticalPaths.takeIf(Set<String>::isNotEmpty)?.let { paths ->
        fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, paths)
    }
    val criticalIncomplete = criticalCoverage is FileStageScopeCoverage.Limited
    val qualified = overallCoverage is FileStageScopeCoverage.Limited
    return KastSourceIndexSummary(
        state = when {
            unmatchedCriticalPatterns.isNotEmpty() || criticalIncomplete -> KastIndexState.FAILED
            qualified -> KastIndexState.DEGRADED
            else -> KastIndexState.READY
        },
        fileCount = snapshot.packageByPath.size,
        identifierCount = snapshot.candidatePathsByIdentifier.size,
        moduleCount = snapshot.moduleNameByPath.values
            .asSequence()
            .filter(String::isNotBlank)
            .map { moduleName -> moduleName.substringBefore("[") }
            .distinct()
            .count(),
        importCount = snapshot.importsByPath.values.sumOf(List<String>::size) +
            snapshot.wildcardImportPackagesByPath.values.sumOf(List<String>::size),
        message = when {
            unmatchedCriticalPatterns.isNotEmpty() ->
                "Critical path patterns matched no indexed source: ${unmatchedCriticalPatterns.joinToString()}"
            criticalIncomplete -> "Critical reference coverage is incomplete"
            qualified -> "Reference coverage is qualified by noncritical gaps"
            else -> null
        },
    )
}
