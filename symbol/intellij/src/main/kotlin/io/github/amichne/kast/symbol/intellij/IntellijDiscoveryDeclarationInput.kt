package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor

internal sealed interface IntellijDiscoveryDeclarationInput {
    val item: NavigationItem

    data class Indexed(override val item: NavigationItem) : IntellijDiscoveryDeclarationInput

    data class Scoped(override val item: NavigationItem) : IntellijDiscoveryDeclarationInput
}

/** Scoped file enumeration ends native callbacks before name/kind admission or PSI projection. */
internal fun IntellijNativeDiscoveryQuery.discoverDeclarations(
    compiledScope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
    process:
        (
            observe: () -> Boolean,
            qualify: (SymbolDiscoveryQualification) -> Unit,
            accept: (NavigationItem) -> Boolean,
        ) -> Boolean,
): IntellijNativeDiscoveryExecution {
    val target = request.target
    if (target !is SymbolDiscoveryTarget.All && target !is SymbolDiscoveryTarget.Name)
        return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT)
    return discoverIndexed(
        compiledScope = compiledScope,
        request = request,
        contributor = IntellijReadContributor.SCOPED_DECLARATIONS,
        process = { observe, qualify, accept ->
            process(observe, qualify) { accept(IntellijDiscoveryDeclarationInput.Scoped(it)) }
        },
    )
}

internal fun IntellijNativeDiscoveryQuery.discoverMixedDeclarations(
    compiledScope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
    process:
        (
            () -> Boolean,
            (SymbolDiscoveryQualification) -> Unit,
            (IntellijDiscoveryDeclarationInput) -> Boolean,
        ) -> Boolean,
): IntellijNativeDiscoveryExecution =
    discoverIndexed(compiledScope, request, IntellijReadContributor.SCOPED_DECLARATIONS, process)

internal fun IntellijNativeDiscoveryQuery.discoverExactName(
    compiledScope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
    process: (String, (NavigationItem) -> Boolean) -> Boolean,
): IntellijNativeDiscoveryExecution {
    val target =
        request.target as? SymbolDiscoveryTarget.Name
            ?: return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT)
    if (target.match != SymbolDiscoveryMatch.EXACT_NAME)
        return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT)
    return discoverIndexed(compiledScope, request, IntellijReadContributor.EXACT_INDEX) { _, _, accept ->
        process(target.pattern.value) { accept(IntellijDiscoveryDeclarationInput.Indexed(it)) }
    }
}
