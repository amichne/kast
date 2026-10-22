@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddressFailure
import io.github.amichne.kast.symbol.contract.LocalDeclarationKind
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature
import io.github.amichne.kast.workspace.intellij.read.namedLocalCompilerOwner
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation

internal fun KaSymbol.localRelationDeclarationAddress(
    session: KaSession,
    file: SymbolDiscoveryFileIdentity,
    depth: Int,
    workAllowance: WorkAllowance,
    projectOwner: (KaSymbol, Int) -> Refinement<CanonicalCompilerSignature, LocalDeclarationProjectionFailure>,
): Refinement<LocalDeclarationAddress, LocalDeclarationProjectionFailure> {
    val declaration = psi ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.SourceUnavailable)
    val range = declaration.textRange ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.SourceUnavailable)
    val owner =
        when (val admitted = localRelationOwner(session, declaration, workAllowance)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val signature =
        when (val projected = projectOwner(owner.symbol, depth + 1)) {
            is Refinement.Refined -> projected.value
            is Refinement.Rejected -> return projected
        }
    return createLocalRelationAddress(file, range, declaration, owner.psi, signature, workAllowance)
}

private data class LocalRelationOwner(val symbol: KaSymbol, val psi: PsiElement)

private fun KaSymbol.localRelationOwner(
    session: KaSession,
    declaration: PsiElement,
    workAllowance: WorkAllowance,
): Refinement<LocalRelationOwner, LocalDeclarationProjectionFailure> {
    val owner =
        when (val selected = namedLocalCompilerOwner(session, workAllowance)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return selected
        }
    val ownerPsi = owner.psi ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerUnavailable)
    if (ownerPsi.containingFile != declaration.containingFile)
        return Refinement.Rejected(
            LocalDeclarationProjectionFailure.InvalidAddress(LocalDeclarationAddressFailure.INVALID_FILE)
        )
    if (owner.location != KaSymbolLocation.LOCAL) {
        when (val proof = owner.localOwnerCompilerTypeProof(workAllowance).asLocalRelationTypeProof()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return proof
        }
    }
    return Refinement.Refined(LocalRelationOwner(owner, ownerPsi))
}

private fun KaSymbol.createLocalRelationAddress(
    file: SymbolDiscoveryFileIdentity,
    range: TextRange,
    declaration: PsiElement,
    owner: PsiElement,
    signature: CanonicalCompilerSignature,
    workAllowance: WorkAllowance,
): Refinement<LocalDeclarationAddress, LocalDeclarationProjectionFailure> {
    val ownerRange = owner.textRange ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerUnavailable)
    val lexical =
        when (val admitted = localDeclarationLexicalOwners(declaration, owner, workAllowance)) {
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
            when (val parsed = range.localRelationRange(LocalDeclarationProjectionFailure.SourceUnavailable)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            },
            CompilerSymbolIdentity.fromCanonicalSignature(signature),
            when (val parsed = ownerRange.localRelationRange(LocalDeclarationProjectionFailure.OwnerUnavailable)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            },
            lexical,
        )
        .mapLocalRelationAddress()
}

private fun TextRange.localRelationRange(
    failure: LocalDeclarationProjectionFailure
): Refinement<ExactDeclarationTextRange, LocalDeclarationProjectionFailure> {
    return when (val parsed = ExactDeclarationTextRange.parse(startOffset, endOffset)) {
        is Refinement.Refined -> parsed
        is Refinement.Rejected -> Refinement.Rejected(failure)
    }
}

private fun Refinement<LocalDeclarationAddress, LocalDeclarationAddressFailure>.mapLocalRelationAddress():
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
