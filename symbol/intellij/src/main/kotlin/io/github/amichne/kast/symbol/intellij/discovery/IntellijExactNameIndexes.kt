package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.util.indexing.IdFilter
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinPropertyShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinTypeAliasShortNameIndex
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

/** Index callbacks only collect candidates; PSI projection and K2 run after they return. */
internal fun IntellijNativeDiscoveryQuery.discoverNative(
    project: Project,
    scope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
): IntellijNativeDiscoveryExecution {
    if (scope.population == IntellijScopePopulation.KNOWN_EMPTY) return discover(scope, request, emptyList())
    val target = request.target
    return if (target is SymbolDiscoveryTarget.All && target.kind != SymbolNameDiscoveryKind.FILE) {
        discoverAll(scope, request) { accept ->
            val files = ArrayList<com.intellij.openapi.vfs.VirtualFile>()
            val complete =
                FileTypeIndex.processFiles(
                    KotlinFileType.INSTANCE,
                    {
                        ProgressManager.checkCanceled()
                        if (files.size.toLong() >= request.budget.resources.workUnitLimit.value) false
                        else {
                            files += it
                            true
                        }
                    },
                    scope.nativeScope,
                )
            val manager = PsiManager.getInstance(project)
            var projected = true
            fun visit(declaration: KtNamedDeclaration): Boolean {
                ProgressManager.checkCanceled()
                when (declaration) {
                    is KtClassOrObject -> {
                        if (!accept(declaration)) return false
                        if (target.kind == SymbolNameDiscoveryKind.SYMBOL) {
                            for (parameter in declaration.primaryConstructorParameters) {
                                if (parameter.hasValOrVar() && !accept(parameter)) return false
                            }
                        }
                        for (child in declaration.declarations.filterIsInstance<KtNamedDeclaration>()) {
                            if (!visit(child)) return false
                        }
                    }
                    is KtNamedFunction,
                    is KtProperty,
                    is KtTypeAlias ->
                        if (target.kind == SymbolNameDiscoveryKind.SYMBOL && !accept(declaration)) return false
                }
                return true
            }
            // File-type index callbacks have ended. Only scoped files are opened as PSI;
            // package proof is checked before collecting declarations or spending candidate capacity.
            for (file in files.sortedBy { it.path }) {
                ProgressManager.checkCanceled()
                val ktFile = manager.findFile(file) as? KtFile
                if (ktFile == null) {
                    projected = false
                    continue
                }
                val restriction = request.constraints.packageName
                if (restriction != null) {
                    val name = ktFile.packageFqName.asString()
                    val wanted = restriction.packageName.value
                    val matches =
                        when (restriction.containment) {
                            SymbolDiscoveryContainment.DIRECT -> name == wanted
                            SymbolDiscoveryContainment.DESCENDANTS -> name == wanted || name.startsWith("$wanted.")
                        }
                    if (!matches) continue
                }
                for (declaration in ktFile.declarations.filterIsInstance<KtNamedDeclaration>()) {
                    if (!visit(declaration)) return@discoverAll false
                }
            }
            complete && projected
        }
    } else if (target is SymbolDiscoveryTarget.Name && target.match == SymbolDiscoveryMatch.EXACT_NAME) {
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
            scope,
            request,
            target.discoveryKind().nativeContributors().filter(target.discoveryKind()::isAdmittedContributor),
            nameFilter,
        )
    }
}
