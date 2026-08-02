@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.references

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.idea.runIdeaReadAction
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.usageSiteDeclarationScope
import org.jetbrains.kotlin.psi.KtFile
import java.util.concurrent.CancellationException

internal fun KastIndexerBackend.indexedReferenceRowInScope(
    row: SymbolReferenceRow,
    searchScope: GlobalSearchScope,
): Boolean {
    if (!isWorkspaceFile(row.sourcePath)) return false
    val virtualFile = LocalFileSystem.getInstance().findFileByPath(row.sourcePath) ?: return false
    return virtualFile.isValid && !virtualFile.isDirectory && searchScope.contains(virtualFile)
}

internal fun KastIndexerBackend.indexedReferenceLocations(
    rows: List<SymbolReferenceRow>,
    includeUsageSiteScope: Boolean,
): List<ReferenceOccurrence> {
    val locations = mutableListOf<ReferenceOccurrence>()
    for (batch in rows.chunked(READ_ACTION_BATCH_SIZE)) {
        val batchLocations = runIdeaReadAction {
            batch.mapNotNull { row -> indexedReferenceLocationOrNull(row, includeUsageSiteScope) }
        }
        locations.addAll(batchLocations)
    }
    return locations
        .distinctBy { it.location.key() }
        .sortedWith(referenceOccurrenceOrder)
}

internal fun KastIndexerBackend.indexedReferenceLocationOrNull(
    row: SymbolReferenceRow,
    includeUsageSiteScope: Boolean,
): ReferenceOccurrence? = try {
    indexedReferenceLocation(row, includeUsageSiteScope)
} catch (error: ProcessCanceledException) {
    throw error
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}

internal fun KastIndexerBackend.indexedReferenceLocation(
    row: SymbolReferenceRow,
    includeUsageSiteScope: Boolean,
): ReferenceOccurrence? {
    if (!isWorkspaceFile(row.sourcePath)) return null
    val file = findKtFile(row.sourcePath)
    val sourceOffset = row.sourceOffset.coerceIn(0, file.textLength)
    val anchor = file.findElementAt(sourceOffset) ?: return null
    val reference = anchor.referenceAtOffset(sourceOffset)
    val element = reference?.element ?: anchor
    if (!element.isValid) return null
    val range = reference?.absoluteTextRange() ?: indexedFallbackRange(file, row)
    val location = element.toKastLocation(range)
    val enrichedLocation = if (includeUsageSiteScope) {
        location.copy(usageSiteScope = element.usageSiteDeclarationScope())
    } else {
        location
    }
    return ReferenceOccurrence(
        location = enrichedLocation,
        containingSymbol = element.containingSymbolEvidence(),
    )
}

internal fun KastIndexerBackend.indexedFallbackRange(
    file: KtFile,
    row: SymbolReferenceRow,
): TextRange {
    val start = row.sourceOffset.coerceIn(0, file.textLength)
    val nameLength = row.targetFqName.substringAfterLast('.').length.coerceAtLeast(1)
    val end = (start + nameLength).coerceAtMost(file.textLength)
    return TextRange(start, end)
}
