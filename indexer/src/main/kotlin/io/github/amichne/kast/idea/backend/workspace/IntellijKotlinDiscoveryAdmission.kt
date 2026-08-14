@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.workspace

import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.selector.selectorOperationFamilies
import io.github.amichne.kast.server.PublicSymbolReadMatch
import io.github.amichne.kast.server.PublicSymbolReadQuery
import io.github.amichne.kast.symbol.intellij.IntellijDiscoveryItemAdmission
import io.github.amichne.kast.symbol.intellij.IntellijDiscoveryItemAdmissionPolicy
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

private sealed interface IntellijKotlinNavigationDeclaration {
    data class Found(
        val declaration: KtNamedDeclaration,
    ) : IntellijKotlinNavigationDeclaration

    data object Unsupported : IntellijKotlinNavigationDeclaration
}

@JvmInline
private value class NormalizedKotlinIdentity private constructor(
    val value: String,
) {
    val isQualified: Boolean
        get() = '.' in value

    companion object {
        /**
         * Proof transition: `String -> NormalizedKotlinIdentity`.
         *
         * Establishes Kotlin identity equivalence after removing optional backticks from every
         * name segment. Raw text may be extracted only for native PSI identity comparison.
         */
        fun from(raw: String): NormalizedKotlinIdentity =
            NormalizedKotlinIdentity(
                raw.split('.').joinToString(".") { segment -> segment.removeSurrounding("`") },
            )
    }
}

private sealed interface IntellijKotlinQualifiedIdentity {
    data class Available(
        val identity: NormalizedKotlinIdentity,
    ) : IntellijKotlinQualifiedIdentity

    data object Unavailable : IntellijKotlinQualifiedIdentity
}

/**
 * Proof transition: `PublicSymbolReadQuery -> IntellijDiscoveryItemAdmissionPolicy`.
 *
 * Establishes a request-local capability that admits only Kotlin declarations with a usable
 * selector family and applies precise fully qualified identity and [SymbolKind] constraints before
 * returned-record accounting. Unsupported declaration shapes remain the closed
 * [IntellijDiscoveryItemAdmission.UNSUPPORTED] state. Raw PSI is inspected only when the native
 * provider streams one item inside the IntelliJ read action.
 */
internal fun PublicSymbolReadQuery.nativeItemAdmission(): IntellijDiscoveryItemAdmissionPolicy {
    val requestedIdentity = NormalizedKotlinIdentity.from(pattern.value)
    return IntellijDiscoveryItemAdmissionPolicy policy@{ item ->
        val declaration = when (val found = item.kotlinDeclaration()) {
            is IntellijKotlinNavigationDeclaration.Found -> found.declaration
            IntellijKotlinNavigationDeclaration.Unsupported -> {
                return@policy IntellijDiscoveryItemAdmission.UNSUPPORTED
            }
        }
        val declarationKind = declaration.publicSymbolKind()
        when {
            declarationKind.selectorOperationFamilies().isEmpty() ->
                IntellijDiscoveryItemAdmission.UNSUPPORTED
            kind != null && declarationKind != kind ->
                IntellijDiscoveryItemAdmission.FILTERED
            match == PublicSymbolReadMatch.EXACT_NAME &&
            requestedIdentity.isQualified &&
            declaration.qualifiedIdentity() !=
            IntellijKotlinQualifiedIdentity.Available(requestedIdentity) ->
                IntellijDiscoveryItemAdmission.FILTERED
            else -> IntellijDiscoveryItemAdmission.ADMITTED
        }
    }
}

/**
 * Proof transition: `NavigationItem -> IntellijKotlinNavigationDeclaration`.
 *
 * Establishes a direct or navigation-target Kotlin named declaration, or the closed unsupported
 * state. Live PSI remains inside the request-local native read.
 */
private fun NavigationItem.kotlinDeclaration(): IntellijKotlinNavigationDeclaration = when (this) {
    is KtNamedDeclaration -> IntellijKotlinNavigationDeclaration.Found(this)
    is PsiElementNavigationItem ->
        (targetElement as? KtNamedDeclaration)
            ?.let(IntellijKotlinNavigationDeclaration::Found)
        ?: IntellijKotlinNavigationDeclaration.Unsupported
    else -> IntellijKotlinNavigationDeclaration.Unsupported
}

private fun KtNamedDeclaration.publicSymbolKind(): SymbolKind = when (this) {
    is KtObjectDeclaration -> SymbolKind.OBJECT
    is KtClass -> if (isInterface()) SymbolKind.INTERFACE else SymbolKind.CLASS
    is KtNamedFunction -> SymbolKind.FUNCTION
    is KtProperty -> SymbolKind.PROPERTY
    is KtParameter -> SymbolKind.PARAMETER
    else -> SymbolKind.UNKNOWN
}

/**
 * Proof transition: `KtNamedDeclaration -> IntellijKotlinQualifiedIdentity`.
 *
 * Establishes explicit normalized fully qualified identity availability. Raw K2 identity text is
 * extracted only for the native request admission comparison.
 */
private fun KtNamedDeclaration.qualifiedIdentity(): IntellijKotlinQualifiedIdentity =
    fqName?.asString()?.let(NormalizedKotlinIdentity::from)
        ?.let(IntellijKotlinQualifiedIdentity::Available)
    ?: IntellijKotlinQualifiedIdentity.Unavailable
