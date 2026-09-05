@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaIdeApi::class)

package kast.example.binding

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.psi.KtNamedDeclaration

private enum class SourceRole { CONSTRUCTOR, FUNCTION, PROPERTY, TYPE_ALIAS, CLASS_LIKE }
private fun KaSymbol.sourceRole(): SourceRole? = when (this) {
    is KaConstructorSymbol -> SourceRole.CONSTRUCTOR
    is KaFunctionSymbol -> SourceRole.FUNCTION
    is KaKotlinPropertySymbol -> SourceRole.PROPERTY
    is KaTypeAliasSymbol -> SourceRole.TYPE_ALIAS
    is KaClassLikeSymbol -> SourceRole.CLASS_LIKE
    else -> null
}

/**
 * Focused replacement for the current SOURCE-only topology comparison.
 *
 * Call inside the reference's analyze block, after the existing exact-generation/file-content
 * admission. The lookup MUST independently load the registry's declaration
 * location, not obtained by taking resolved.psi and assigning it a registry identity.
 *
 * This example deliberately preserves the current origin scope. Do not add blanket
 * fakeOverrideOriginal normalization: intersections, delegation, constructors and generated
 * members require their own relationship semantics and coverage tests. directlyOverriddenSymbols
 * already unwraps substitution/intersection overrides according to its API contract.
 *
 * No symbol or session is retained. Only the detached binding result leaves this function.
 */
fun KaSession.bindRegisteredSource(
    entry: RegistryEntry,
    currentEpoch: Epoch,
    resolved: KaSymbol,
    lookup: RegisteredSourceLookup,
): BindingResult {
    val authority = CompilerAuthority<KaSymbol> { candidate, target ->
        when (val loaded = lookup.find(candidate)) {
            RegisteredSourceLookupResult.Unavailable ->
                CompilerComparison.Rejected(Difference.SOURCE_UNAVAILABLE)
            is RegisteredSourceLookupResult.Found -> {
                val declaration = loaded.declaration.symbol
                val targetRole = target.sourceRole()
                val declarationRole = declaration.sourceRole()
                when {
                    target.origin == KaSymbolOrigin.INTERSECTION_OVERRIDE ->
                        CompilerComparison.Rejected(Difference.MULTIPLE_DECLARATIONS)
                    target.origin != KaSymbolOrigin.SOURCE || declaration.origin != KaSymbolOrigin.SOURCE ->
                        CompilerComparison.Rejected(Difference.ORIGIN_NOT_ADMITTED)
                    targetRole == null || declarationRole == null ->
                        CompilerComparison.Rejected(Difference.SOURCE_UNAVAILABLE)
                    targetRole != declarationRole ->
                        CompilerComparison.Rejected(Difference.DIFFERENT_ROLE)
                    target.containingModule != declaration.containingModule ->
                        CompilerComparison.Rejected(Difference.DIFFERENT_MODULE)
                    target != declaration ->
                        CompilerComparison.Rejected(Difference.DIFFERENT_DECLARATION)
                    else -> CompilerComparison.SameDeclaration
                }
            }
        }
    }
    return ProvenBinding.bind(entry, currentEpoch, resolved, authority)
}

/** Existing file-content and exact-range admission must precede Found. Never locate by name. */
sealed interface RegisteredSourceLookupResult {
    data class Found(val declaration: KtNamedDeclaration) : RegisteredSourceLookupResult
    data object Unavailable : RegisteredSourceLookupResult
}
fun interface RegisteredSourceLookup {
    fun find(entry: RegistryEntry): RegisteredSourceLookupResult
}
