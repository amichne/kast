package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameRelevance
import io.github.amichne.kast.symbol.contract.relevance
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinPropertyShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinTypeAliasShortNameIndex

/** Index callbacks admit only cheap facts; the shared collector projects after every callback finishes. */
internal fun collectIndexedNamedDeclarations(
    project: Project,
    scope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
    observe: () -> Boolean,
    qualify: (SymbolDiscoveryQualification) -> Unit,
    limits: ReadLimits,
    accept: (NavigationItem) -> Boolean,
): Boolean {
    val target = request.target as? SymbolDiscoveryTarget.Name ?: return false
    return when (target.match) {
        SymbolDiscoveryMatch.EXACT_NAME ->
            collectExactDeclarations(project, scope, request, target.pattern.value, accept)
        SymbolDiscoveryMatch.FUZZY -> {
            val filter = IdFilter.getProjectIdFilter(project, true)
            target.kind
                .nativeContributors()
                .filter { request.admitsContributorName(it.javaClass.name) }
                .all { contributor ->
                    if (!observe()) false
                    else if (contributor !is ChooseByNameContributorEx) {
                        qualify(SymbolDiscoveryQualification.UNSCOPED_PROVIDER)
                        true
                    } else
                        collectContributorDeclarations(
                            contributor,
                            scope,
                            request,
                            observe,
                            qualify,
                            limits,
                            filter,
                            accept,
                        )
                }
        }
    }
}

private fun collectExactDeclarations(
    project: Project,
    scope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
    name: String,
    accept: (NavigationItem) -> Boolean,
): Boolean {
    val kinds = request.requestedDeclarationKinds()
    return (CompilerSymbolKind.CLASSLIKE !in kinds ||
        KotlinClassShortNameIndex.processElements(name, project, scope.nativeScope) { accept(it) }) &&
        (CompilerSymbolKind.FUNCTION !in kinds ||
            KotlinFunctionShortNameIndex.processElements(name, project, scope.nativeScope) { accept(it) }) &&
        (CompilerSymbolKind.PROPERTY !in kinds ||
            KotlinPropertyShortNameIndex.processElements(name, project, scope.nativeScope) { accept(it) }) &&
        (CompilerSymbolKind.TYPE_ALIAS !in kinds ||
            KotlinTypeAliasShortNameIndex.processElements(name, project, scope.nativeScope) { accept(it) })
}

private fun collectContributorDeclarations(
    contributor: ChooseByNameContributorEx,
    scope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
    observe: () -> Boolean,
    qualify: (SymbolDiscoveryQualification) -> Unit,
    limits: ReadLimits,
    filter: IdFilter?,
    accept: (NavigationItem) -> Boolean,
): Boolean {
    val target = request.target as? SymbolDiscoveryTarget.Name ?: return false
    val names =
        when (val admitted = collectContributorNames(contributor, scope, target, observe, qualify, limits, filter)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return false
        }
    return names.all { name ->
        var completed = true
        contributor.processElementsWithName(
            name,
            Processor {
                val admitted = observe() && accept(it)
                if (!admitted) completed = false
                admitted
            },
            FindSymbolParameters.wrap(name, scope.nativeScope),
        )
        completed
    }
}

private enum class ContributorNameFailure {
    OBSERVATION_STOPPED,
    NAME_LIMIT_REACHED,
}

private fun collectContributorNames(
    contributor: ChooseByNameContributorEx,
    scope: CompiledIntellijSearchScope,
    target: SymbolDiscoveryTarget.Name,
    observe: () -> Boolean,
    qualify: (SymbolDiscoveryQualification) -> Unit,
    limits: ReadLimits,
    filter: IdFilter?,
): Refinement<Set<String>, ContributorNameFailure> {
    val names = linkedSetOf<String>()
    var completion: Refinement<Unit, ContributorNameFailure> = Refinement.Refined(Unit)
    contributor.processNames(
        Processor { name ->
            if (!observe()) {
                completion = Refinement.Rejected(ContributorNameFailure.OBSERVATION_STOPPED)
                return@Processor false
            }
            if (target.pattern.relevance(name) == SymbolNameRelevance.UNMATCHED) return@Processor true
            if (name !in names && names.size >= limits[ReadLimitParameter.DISCOVERY_NAMES].value) {
                qualify(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
                completion = Refinement.Rejected(ContributorNameFailure.NAME_LIMIT_REACHED)
                return@Processor false
            }
            names.add(name)
            true
        },
        scope.nativeScope,
        filter ?: IdFilter.ACCEPT_ALL,
    )
    return when (val admitted = completion) {
        is Refinement.Refined -> Refinement.Refined(names)
        is Refinement.Rejected -> admitted
    }
}
