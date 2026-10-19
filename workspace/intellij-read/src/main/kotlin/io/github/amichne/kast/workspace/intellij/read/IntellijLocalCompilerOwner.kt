@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.containingSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** Anonymous functions remain lexical scopes even when their PSI exposes a synthetic name. */
fun KaSymbol.namedLocalCompilerOwner(session: KaSession): Refinement<KaSymbol, LocalDeclarationProjectionFailure> =
    with(session) {
        var owner = containingSymbol
        var traversed = 0
        while (owner != null && !owner.isLocalDeclarationOwner()) {
            if (++traversed > LocalDeclarationAddress.MAX_OWNER_DEPTH)
                return Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerDepthExceeded)
            owner = owner.containingSymbol
        }
        return if (owner == null) Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerUnavailable)
        else Refinement.Refined(owner)
    }

private fun KaSymbol.isLocalDeclarationOwner(): Boolean =
    when (this) {
        is KaAnonymousFunctionSymbol -> false
        is KaConstructorSymbol,
        is KaAnonymousObjectSymbol -> true
        else -> (psi as? KtNamedDeclaration)?.name != null
    }
