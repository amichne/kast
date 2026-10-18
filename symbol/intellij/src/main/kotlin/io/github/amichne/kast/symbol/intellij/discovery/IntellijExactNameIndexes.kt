package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.util.indexing.IdFilter
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor

/** Index callbacks only collect candidates; PSI projection and K2 run after they return. */
internal fun IntellijNativeDiscoveryQuery.discoverNative(
    project: Project,
    scope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
    allowance: IntellijDeclarationDiscoveryAllowance = IntellijDeclarationDiscoveryAllowance(request),
    indexes: IntellijDeclarationIndexes = NativeIntellijDeclarationIndexes(project),
    partitions: IntellijDeclarationPartitionFiles = NativeIntellijDeclarationPartitionFiles,
): IntellijNativeDiscoveryExecution {
    if (scope.population == IntellijScopePopulation.KNOWN_EMPTY) return discover(scope, request, emptyList())
    when (val route = IntellijDirectoryDiscoveryPlan.select(scope, request)) {
        is IntellijDirectoryDiscoveryRoute.Selected -> {
            val target = route.plan.target
            if (target.match == SymbolDiscoveryMatch.EXACT_NAME) {
                when (val indexed = IntellijIndexedDeclarationName.parse(target.pattern.value)) {
                    is io.github.amichne.kast.kernel.Refinement.Refined ->
                        return discoverScopedDeclarations(scope, request, allowance) { callbacks ->
                            collectExactNameCandidateDeclarations(
                                project,
                                route.plan,
                                indexed.value,
                                indexes,
                                limits,
                                callbacks,
                                allowance,
                                partitions,
                            )
                        }
                    is io.github.amichne.kast.kernel.Refinement.Rejected -> Unit
                }
            }
            return discoverDirectory(project, route.plan, allowance, partitions)
        }
        IntellijDirectoryDiscoveryRoute.Rejected ->
            return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT)
        IntellijDirectoryDiscoveryRoute.Existing -> Unit
    }
    val target = request.target
    return if (target is SymbolDiscoveryTarget.All && target.kind != SymbolNameDiscoveryKind.FILE) {
        discoverIncrementalScopedDeclarations(project, scope, request, limits, allowance, observation)
    } else if (
        target is SymbolDiscoveryTarget.Name &&
            target.kind != SymbolNameDiscoveryKind.FILE &&
            target.match == SymbolDiscoveryMatch.EXACT_NAME
    ) {
        discoverExactIndexedDeclarations(project, scope, request, indexes)
    } else if (request.usesScopedDeclarationEnumeration()) {
        discoverDeclarations(scope, request) { observe, qualify, accept ->
            collectScopedKotlinDeclarations(
                project = project,
                scope = scope,
                request = request,
                callbacks = ScopedDeclarationCallbacks(observe, qualify, accept, observation),
                limits = limits,
                indexes = indexes,
            )
        }
    } else if (target is SymbolDiscoveryTarget.Name && target.kind != SymbolNameDiscoveryKind.FILE) {
        discoverMixedDeclarations(scope, request) { observe, qualify, accept ->
            val complete =
                collectIndexedNamedDeclarations(
                    project,
                    scope,
                    request,
                    observe,
                    qualify,
                    limits,
                    indexes,
                    observation,
                ) {
                    accept(IntellijDiscoveryDeclarationInput.Indexed(it))
                }
            complete &&
                collectScopedKotlinDeclarations(
                    project,
                    scope,
                    request,
                    ScopedDeclarationCallbacks(
                        observe,
                        qualify,
                        observation = observation,
                        accept = {
                            accept(IntellijDiscoveryDeclarationInput.Scoped(it))
                        },
                    ),
                    limits,
                    indexes = indexes,
                    localOnly = true,
                )
        }
    } else if (
        target is SymbolDiscoveryTarget.Name &&
            target.kind == SymbolNameDiscoveryKind.FILE &&
            target.match == SymbolDiscoveryMatch.EXACT_NAME
    ) {
        discoverExactName(scope, request) { name, accept ->
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

internal fun IntellijNativeDiscoveryQuery.discoverExactIndexedDeclarations(
    project: Project,
    scope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
    indexes: IntellijDeclarationIndexes = NativeIntellijDeclarationIndexes(project),
): IntellijNativeDiscoveryExecution =
    discoverIndexed(
        compiledScope = scope,
        request = request,
        contributor = IntellijReadContributor.EXACT_INDEX,
        process = { observe, qualify, accept ->
            collectIndexedNamedDeclarations(
                project,
                scope,
                request,
                observe,
                qualify,
                limits,
                indexes,
                observation,
            ) {
                accept(IntellijDiscoveryDeclarationInput.Indexed(it))
            }
        },
        continuation = { observe, qualify, accept ->
            collectScopedKotlinDeclarations(
                project,
                scope,
                request,
                ScopedDeclarationCallbacks(
                    observe,
                    qualify,
                    observation = observation,
                    accept = {
                        accept(IntellijDiscoveryDeclarationInput.Scoped(it))
                    },
                ),
                limits,
                indexes = indexes,
                localOnly = scope.scope.libraryPolicy() == SymbolLibraryPolicy.INCLUDE,
            )
        },
    )
