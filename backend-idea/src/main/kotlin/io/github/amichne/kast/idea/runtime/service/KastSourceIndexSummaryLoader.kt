package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.diagnostics.KastIndexState
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore

internal fun SqliteSourceIndexStore.loadKastSourceIndexSummary(): KastSourceIndexSummary {
    val snapshot = loadSourceIndexSnapshot()
    return KastSourceIndexSummary(
        state = KastIndexState.READY,
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
    )
}
