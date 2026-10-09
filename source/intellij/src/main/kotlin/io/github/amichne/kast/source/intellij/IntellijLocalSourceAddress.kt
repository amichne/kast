@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.source.intellij

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddressFailure
import io.github.amichne.kast.symbol.contract.LocalDeclarationKind
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.containingSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal fun KaSymbol.localSourceDeclarationAddress(
    session: KaSession,
    file: SymbolDiscoveryFileIdentity,
    depth: Int,
    projectOwner: (KaSymbol, Int) -> Refinement<CanonicalCompilerSignature, LocalDeclarationProjectionFailure>,
): Refinement<LocalDeclarationAddress, LocalDeclarationProjectionFailure> {
    val declaration = psi ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.SourceUnavailable)
    val range = declaration.textRange ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.SourceUnavailable)
    val owner =
        when (val admitted = localSourceOwner(session, declaration)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val signature =
        when (val projected = projectOwner(owner.symbol, depth + 1)) {
            is Refinement.Refined -> projected.value
            is Refinement.Rejected -> return projected
        }
    return createLocalSourceAddress(file, range, declaration, owner.psi, signature)
}

private data class LocalSourceOwner(val symbol: KaSymbol, val psi: PsiElement)

private fun KaSymbol.localSourceOwner(
    session: KaSession,
    declaration: PsiElement,
): Refinement<LocalSourceOwner, LocalDeclarationProjectionFailure> {
    val owner =
        when (val selected = namedLocalSourceOwner(session)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return selected
        }
    val ownerPsi = owner.psi ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerUnavailable)
    if (ownerPsi.containingFile != declaration.containingFile)
        return Refinement.Rejected(
            LocalDeclarationProjectionFailure.InvalidAddress(LocalDeclarationAddressFailure.INVALID_FILE)
        )
    if (owner.location != KaSymbolLocation.LOCAL) {
        when (val proof = owner.localOwnerCompilerTypeProof().asLocalSourceTypeProof()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return proof
        }
    }
    return Refinement.Refined(LocalSourceOwner(owner, ownerPsi))
}

private fun KaSymbol.namedLocalSourceOwner(
    session: KaSession
): Refinement<KaSymbol, LocalDeclarationProjectionFailure> =
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

private fun KaSymbol.createLocalSourceAddress(
    file: SymbolDiscoveryFileIdentity,
    range: TextRange,
    declaration: PsiElement,
    owner: PsiElement,
    signature: CanonicalCompilerSignature,
): Refinement<LocalDeclarationAddress, LocalDeclarationProjectionFailure> {
    val ownerRange = owner.textRange ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerUnavailable)
    val lexical =
        when (val admitted = localDeclarationLexicalOwners(declaration, owner)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val kind =
        when (this) {
            is KaAnonymousObjectSymbol -> LocalDeclarationKind.ANONYMOUS_OBJECT
            is KaNamedFunctionSymbol -> LocalDeclarationKind.FUNCTION
            is KaLocalVariableSymbol -> LocalDeclarationKind.PROPERTY
            else -> return Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerOwnerUnavailable)
        }
    return LocalDeclarationAddress.create(
            file,
            kind,
            when (val parsed = range.localSourceRange(LocalDeclarationProjectionFailure.SourceUnavailable)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            },
            CompilerSymbolIdentity.fromCanonicalSignature(signature),
            when (val parsed = ownerRange.localSourceRange(LocalDeclarationProjectionFailure.OwnerUnavailable)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            },
            lexical,
        )
        .mapLocalSourceAddress()
}

private fun TextRange.localSourceRange(
    failure: LocalDeclarationProjectionFailure
): Refinement<ExactDeclarationTextRange, LocalDeclarationProjectionFailure> {
    return when (val parsed = ExactDeclarationTextRange.parse(startOffset, endOffset)) {
        is Refinement.Refined -> parsed
        is Refinement.Rejected -> Refinement.Rejected(failure)
    }
}

private fun Refinement<LocalDeclarationAddress, LocalDeclarationAddressFailure>.mapLocalSourceAddress():
    Refinement<LocalDeclarationAddress, LocalDeclarationProjectionFailure> =
    when (this) {
        is Refinement.Refined -> this
        is Refinement.Rejected ->
            Refinement.Rejected(
                if (failure == LocalDeclarationAddressFailure.OWNER_DEPTH_EXCEEDED)
                    LocalDeclarationProjectionFailure.OwnerDepthExceeded
                else LocalDeclarationProjectionFailure.InvalidAddress(failure)
            )
    }

private fun KaSymbol.isLocalDeclarationOwner(): Boolean =
    this is KaConstructorSymbol || this is KaAnonymousObjectSymbol || (psi as? KtNamedDeclaration)?.name != null
