package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.util.indexing.IdFilter
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
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
    val kinds = request.requestedDeclarationKinds()
    return if (request.usesScopedDeclarationEnumeration()) {
        discoverDeclarations(scope, request) { observe, qualify, accept ->
            collectScopedKotlinDeclarations(
                project = project,
                scope = scope,
                request = request,
                callbacks = ScopedDeclarationCallbacks(observe, qualify, accept),
                limits = limits,
            )
        }
    } else if (target is SymbolDiscoveryTarget.Name && target.match == SymbolDiscoveryMatch.EXACT_NAME) {
        discoverExactName(scope, request) { name, accept ->
            when (target.kind) {
                SymbolNameDiscoveryKind.CLASS,
                SymbolNameDiscoveryKind.SYMBOL ->
                    (CompilerSymbolKind.CLASSLIKE !in kinds ||
                        KotlinClassShortNameIndex.processElements(name, project, scope.nativeScope) { accept(it) }) &&
                        (CompilerSymbolKind.FUNCTION !in kinds ||
                            KotlinFunctionShortNameIndex.processElements(name, project, scope.nativeScope) {
                                accept(it)
                            }) &&
                        (CompilerSymbolKind.PROPERTY !in kinds ||
                            KotlinPropertyShortNameIndex.processElements(name, project, scope.nativeScope) {
                                accept(it)
                            }) &&
                        (CompilerSymbolKind.TYPE_ALIAS !in kinds ||
                            KotlinTypeAliasShortNameIndex.processElements(name, project, scope.nativeScope) {
                                accept(it)
                            })
                SymbolNameDiscoveryKind.FILE -> {
                    val files = ArrayList<com.intellij.openapi.vfs.VirtualFile>()
                    val complete =
                        FilenameIndex.processFilesByName(name, true, scope.nativeScope) {
                            ProgressManager.checkCanceled()
                            files += it
                            files.size.toLong() <= request.budget.resources.workUnitLimit.value
                        }
                    var projected = true
                    for (file in files) {
                        val item = PsiManager.getInstance(project).findFile(file)
                        if (item == null) projected = false else if (!accept(item)) return@discoverExactName false
                    }
                    complete && projected
                }
            }
        }
    } else {
        // A custom GlobalSearchScope's default key filter includes indexed libraries.
        // This cached file-ID filter narrows name enumeration only; compiled scope and
        // retained constraints still admit each collected item before projection.
        val nameFilter =
            IdFilter.getProjectIdFilter(
                project,
                when (scope.scope.libraryPolicy()) {
                    SymbolLibraryPolicy.EXCLUDE -> false
                    SymbolLibraryPolicy.INCLUDE -> true
                },
            )
        discover(
            compiledScope = scope,
            request = request,
            contributors =
                target.discoveryKind().nativeContributors().filter { request.admitsContributorName(it.javaClass.name) },
            nameFilter = nameFilter,
        )
    }
}
