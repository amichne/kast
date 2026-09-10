package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.util.indexing.IdFilter
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinPropertyShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinTypeAliasShortNameIndex

/** Index callbacks only collect candidates; PSI projection and K2 run after they return. */
internal fun IntellijNativeDiscoveryQuery.discoverNative(
    project: Project,
    scope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
): IntellijNativeDiscoveryExecution {
    if (scope.population == IntellijScopePopulation.KNOWN_EMPTY) return discover(scope, request, emptyList())
    val target = request.target
    return if (target is SymbolDiscoveryTarget.Name && target.match == SymbolDiscoveryMatch.EXACT_NAME) {
        discoverExactName(scope, request) { name, accept ->
            when (target.kind) {
                SymbolNameDiscoveryKind.CLASS ->
                    KotlinClassShortNameIndex.processElements(name, project, scope.nativeScope) { accept(it) }
                SymbolNameDiscoveryKind.SYMBOL ->
                    KotlinClassShortNameIndex.processElements(name, project, scope.nativeScope) { accept(it) } &&
                        KotlinFunctionShortNameIndex.processElements(name, project, scope.nativeScope) { accept(it) } &&
                        KotlinPropertyShortNameIndex.processElements(name, project, scope.nativeScope) { accept(it) } &&
                        KotlinTypeAliasShortNameIndex.processElements(name, project, scope.nativeScope) { accept(it) }
                SymbolNameDiscoveryKind.FILE -> {
                    val files = ArrayList<com.intellij.openapi.vfs.VirtualFile>()
                    val complete = FilenameIndex.processFilesByName(name, true, scope.nativeScope) {
                        ProgressManager.checkCanceled()
                        files += it
                        files.size.toLong() <= request.budget.resources.workUnitLimit.value
                    }
                    var projected = true
                    for (file in files) {
                        val item = PsiManager.getInstance(project).findFile(file)
                        if (item == null) projected = false
                        else if (!accept(item)) return@discoverExactName false
                    }
                    complete && projected
                }
            }
        }
    } else {
        // A custom GlobalSearchScope's default key filter includes indexed libraries.
        // This cached file-ID filter narrows name enumeration only; compiled scope and
        // retained constraints still admit each collected item before projection.
        val nameFilter = IdFilter.getProjectIdFilter(project, when (scope.scope.libraryPolicy()) {
            SymbolLibraryPolicy.EXCLUDE -> false
            SymbolLibraryPolicy.INCLUDE -> true
        })
        discover(scope, request, target.discoveryKind().nativeContributors()
            .filter(target.discoveryKind()::isAdmittedContributor), nameFilter)
    }
}
