package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.diagnostics.KastIndexState
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.indexstore.api.index.RelationshipIndexStatus
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore

internal fun SqliteSourceIndexStore.loadKastSourceIndexSummary(): KastSourceIndexSummary {
    val snapshot = loadSourceIndexSnapshot()
    val moduleStatuses = moduleIndexStatuses().values
    val failedModules = moduleStatuses.count { status -> status == RelationshipIndexStatus.FAILED }
    val degradedModules = moduleStatuses.count { status -> status == RelationshipIndexStatus.DEGRADED }
    return KastSourceIndexSummary(
        state = when {
            failedModules > 0 -> KastIndexState.FAILED
            degradedModules > 0 -> KastIndexState.DEGRADED
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
            failedModules > 0 -> "$failedModules modules require a file-local failure decision"
            degradedModules > 0 -> "$degradedModules modules contain external graph boundaries"
            else -> null
        },
    )
}
