@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignatureFailure
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.containingSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.name.FqName

/** The enum fallback affects a local address's containing owner, never global qualified admission. */
internal fun KaSymbol.localRelationOwnerSignature(
    session: KaSession,
    file: SymbolDiscoveryFileIdentity,
    depth: Int,
    observation: IntellijReadObservation,
): Refinement<CanonicalCompilerSignature, LocalDeclarationProjectionFailure> {
    if (depth > LocalDeclarationAddress.MAX_OWNER_DEPTH)
        return Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerDepthExceeded)
    if (location == KaSymbolLocation.LOCAL || with(session) { containingSymbol is KaAnonymousObjectSymbol })
        return compilerProjection(session, file, depth, observation).ownerSignature()
    return when (this) {
        is KaNamedFunctionSymbol -> localOwnerIdentity(session, observation).mapOwnerSignature { functionSignature(it) }
        is KaKotlinPropertySymbol ->
            localOwnerIdentity(session, observation).mapOwnerSignature { propertySignature(it) }
        else -> compilerProjection(session, file, depth, observation).ownerSignature()
    }
}

private fun KaCallableSymbol.localOwnerIdentity(
    session: KaSession,
    observation: IntellijReadObservation,
) = localOwnerCallableIdentity(session).observedLocalOwnerIdentity(observation)

private fun Refinement<FqName, LocalDeclarationProjectionFailure>.mapOwnerSignature(
    signature:
        (String) -> Refinement<
                CanonicalCompilerSignature,
                CanonicalCompilerSignatureFailure,
            >
): Refinement<CanonicalCompilerSignature, LocalDeclarationProjectionFailure> =
    when (this) {
        is Refinement.Refined ->
            when (val result = signature(value.asString())) {
                is Refinement.Refined -> result
                is Refinement.Rejected -> Refinement.Rejected(LocalDeclarationProjectionFailure.SignatureUnavailable)
            }
        is Refinement.Rejected -> this
    }

private fun IntellijCompilerProjectionResult.ownerSignature():
    Refinement<CanonicalCompilerSignature, LocalDeclarationProjectionFailure> =
    when (this) {
        is IntellijCompilerProjectionResult.Projected -> Refinement.Refined(projection.signature)
        is IntellijCompilerProjectionResult.LocalRejected -> Refinement.Rejected(failure)
        IntellijCompilerProjectionResult.Unsupported ->
            Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerOwnerUnavailable)
    }
