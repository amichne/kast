package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.diagnostics.KastIndexState
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.api.contract.ReferenceCoverageLimitation
import io.github.amichne.kast.api.client.fields.WorkspaceIndexingPattern
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageScopeCoverage
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore

internal fun SqliteSourceIndexStore.loadKastSourceIndexSummary(
    criticalPaths: Set<WorkspaceSourcePath> = emptySet(),
    unmatchedCriticalPatterns: List<WorkspaceIndexingPattern> = emptyList(),
): KastSourceIndexSummary {
    val snapshot = loadSourceIndexSnapshot()
    val indexedPaths = knownSourcePaths().mapNotNullTo(linkedSetOf(), ::sourcePath)
    val overallCoverage = indexedPaths.takeIf { paths -> paths.isNotEmpty() }?.let { paths ->
        fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, paths)
    }
    val criticalCoverage = criticalPaths.takeIf { paths -> paths.isNotEmpty() }?.let { paths ->
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
        moduleCount = snapshot.moduleByPath.values
            .asSequence()
            .map { module -> module.name }
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
        referenceCoverageLimitations = when {
            unmatchedCriticalPatterns.isNotEmpty() ->
                listOf(ReferenceCoverageLimitation.UNMATCHED_CRITICAL_PATH)
            criticalIncomplete -> listOf(ReferenceCoverageLimitation.CRITICAL_STAGE_GAP)
            qualified -> listOf(ReferenceCoverageLimitation.NONCRITICAL_STAGE_GAP)
            else -> emptyList()
        },
    )
}
