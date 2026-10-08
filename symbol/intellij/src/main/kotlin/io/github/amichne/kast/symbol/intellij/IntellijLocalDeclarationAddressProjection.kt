@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.symbol.intellij

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
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.containingSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal fun KaSymbol.localAddress(
    session: KaSession,
    observation: IntellijReadObservation,
    file: SymbolDiscoveryFileIdentity,
    depth: Int,
): Refinement<LocalDeclarationAddress, LocalDeclarationProjectionFailure> {
    val declaration = psi ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.SourceUnavailable)
    val range = declaration.textRange ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.SourceUnavailable)
    val owner =
        when (val admitted = localCompilerOwner(session, declaration)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val signature =
        when (val projected = owner.symbol.localOwnerSignature(session, observation, file, depth + 1)) {
            is Refinement.Refined -> projected.value
            is Refinement.Rejected -> return projected
        }
    return createLocalCompilerAddress(file, range, declaration, owner.psi, signature)
}

private data class LocalCompilerOwner(val symbol: KaSymbol, val psi: PsiElement)

private fun KaSymbol.localCompilerOwner(
    session: KaSession,
    declaration: PsiElement,
): Refinement<LocalCompilerOwner, LocalDeclarationProjectionFailure> {
    val owner =
        when (val selected = namedLocalCompilerOwner(session)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return selected
        }
    val ownerPsi = owner.psi ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerUnavailable)
    if (ownerPsi.containingFile != declaration.containingFile)
        return Refinement.Rejected(
            LocalDeclarationProjectionFailure.InvalidAddress(LocalDeclarationAddressFailure.INVALID_FILE)
        )
    if (owner.location != KaSymbolLocation.LOCAL) {
        when (val proof = owner.localOwnerCompilerTypeProof().asLocalDeclarationTypeProof()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return proof
        }
    }
    return Refinement.Refined(LocalCompilerOwner(owner, ownerPsi))
}

private fun KaSymbol.namedLocalCompilerOwner(
    session: KaSession
): Refinement<KaSymbol, LocalDeclarationProjectionFailure> =
    with(session) {
        var owner = containingSymbol
        var traversed = 0
        while (owner != null && owner !is KaConstructorSymbol && (owner.psi as? KtNamedDeclaration)?.name == null) {
            if (++traversed > LocalDeclarationAddress.MAX_OWNER_DEPTH)
                return Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerDepthExceeded)
            owner = owner.containingSymbol
        }
        return if (owner == null) Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerUnavailable)
        else Refinement.Refined(owner)
    }

private fun KaSymbol.createLocalCompilerAddress(
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
            is KaNamedFunctionSymbol -> LocalDeclarationKind.FUNCTION
            is KaLocalVariableSymbol -> LocalDeclarationKind.PROPERTY
            else -> return Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerOwnerUnavailable)
        }
    return LocalDeclarationAddress.create(
            file,
            kind,
            when (val parsed = range.localCompilerRange(LocalDeclarationProjectionFailure.SourceUnavailable)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            },
            CompilerSymbolIdentity.fromCanonicalSignature(signature),
            when (val parsed = ownerRange.localCompilerRange(LocalDeclarationProjectionFailure.OwnerUnavailable)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            },
            lexical,
        )
        .mapLocalCompilerAddress()
}

private fun TextRange.localCompilerRange(
    failure: LocalDeclarationProjectionFailure
): Refinement<ExactDeclarationTextRange, LocalDeclarationProjectionFailure> {
    return when (val parsed = ExactDeclarationTextRange.parse(startOffset, endOffset)) {
        is Refinement.Refined -> parsed
        is Refinement.Rejected -> Refinement.Rejected(failure)
    }
}

private fun Refinement<LocalDeclarationAddress, LocalDeclarationAddressFailure>.mapLocalCompilerAddress():
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

private fun KaSymbol.localOwnerSignature(
    session: KaSession,
    observation: IntellijReadObservation,
    file: SymbolDiscoveryFileIdentity,
    depth: Int,
): Refinement<CanonicalCompilerSignature, LocalDeclarationProjectionFailure> =
    when (val result = toCompilerProjection(session, observation, file, depth)) {
        is IntellijCompilerSymbolProjectionResult.Projected -> Refinement.Refined(result.projection.signature)
        is IntellijCompilerSymbolProjectionResult.Rejected ->
            Refinement.Rejected(
                if (result.reason == IntellijSymbolSelectorRejection.WORK_LIMIT_REACHED)
                    LocalDeclarationProjectionFailure.WorkLimitReached
                else LocalDeclarationProjectionFailure.CompilerOwnerUnavailable
            )
    }
