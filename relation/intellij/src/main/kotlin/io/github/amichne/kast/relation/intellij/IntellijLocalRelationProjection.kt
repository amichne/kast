@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddressFailure
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import io.github.amichne.kast.symbol.contract.LocalPropertyMutability
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

internal fun KaSymbol.localRelationProjection(
    session: KaSession,
    admittedFile: SymbolDiscoveryFileIdentity?,
    depth: Int,
    observation: IntellijReadObservation,
): IntellijCompilerProjectionResult {
    if (!hasSupportedLocalDeclaration())
        return IntellijCompilerProjectionResult.LocalRejected(LocalDeclarationProjectionFailure.UnsupportedDeclaration)
    when (val proof = localRelationCompilerTypeProof()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return IntellijCompilerProjectionResult.LocalRejected(proof.failure)
    }
    val file =
        when (val restored = localRelationFile(admittedFile)) {
            is Refinement.Refined -> restored.value
            is Refinement.Rejected -> return IntellijCompilerProjectionResult.LocalRejected(restored.failure)
        }
    val address =
        when (
            val admitted =
                localRelationDeclarationAddress(session, file, depth) { owner, ownerDepth ->
                    owner.localRelationOwnerSignature(session, file, ownerDepth, observation)
                }
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return IntellijCompilerProjectionResult.LocalRejected(admitted.failure)
        }
    return when (val projection = localRelationSignature(address)) {
        is IntellijCompilerProjectionResult.Projected -> projection
        is IntellijCompilerProjectionResult.LocalRejected,
        IntellijCompilerProjectionResult.Unsupported ->
            IntellijCompilerProjectionResult.LocalRejected(LocalDeclarationProjectionFailure.SignatureUnavailable)
    }
}

private fun KaSymbol.hasSupportedLocalDeclaration(): Boolean =
    when (this) {
        is KaAnonymousObjectSymbol -> (psi as? org.jetbrains.kotlin.psi.KtObjectDeclaration)?.isObjectLiteral() == true
        is KaNamedFunctionSymbol -> psi is KtNamedFunction && (psi as KtNamedFunction).name != null
        is KaLocalVariableSymbol -> psi is KtProperty && (psi as KtProperty).isLocal
        else -> false
    }

private fun KaSymbol.localRelationFile(
    admittedFile: SymbolDiscoveryFileIdentity?
): Refinement<SymbolDiscoveryFileIdentity, LocalDeclarationProjectionFailure> {
    val virtual =
        psi?.containingFile?.virtualFile
            ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.SourceUnavailable)
    if (admittedFile != null) return Refinement.Refined(admittedFile)
    val path = virtual.fileSystem.protocol.takeIf { it == "file" }?.let { virtual.path }
    return when (
        val parsed =
            LocalDeclarationAddress.restoreFile(if (path != null) "workspace" else "external", path ?: virtual.url)
    ) {
        is Refinement.Refined -> parsed
        is Refinement.Rejected ->
            Refinement.Rejected(
                LocalDeclarationProjectionFailure.InvalidAddress(LocalDeclarationAddressFailure.INVALID_FILE)
            )
    }
}

private fun KaSymbol.localRelationSignature(address: LocalDeclarationAddress): IntellijCompilerProjectionResult =
    when (this) {
        is KaAnonymousObjectSymbol ->
            projected(
                CompilerSymbolKind.CLASSLIKE,
                null,
                CanonicalCompilerSignature.anonymousObject(address, superTypes.map { it.toString() }),
            )
        is KaNamedFunctionSymbol ->
            projected(
                CompilerSymbolKind.FUNCTION,
                null,
                CanonicalCompilerSignature.localFunction(
                    address,
                    receiverParameter?.returnType?.toString(),
                    contextReceivers.map { it.type.toString() },
                    valueParameters.map { it.returnType.toString() },
                    typeParameters.size,
                    returnType.toString(),
                ),
            )
        is KaLocalVariableSymbol ->
            projected(
                CompilerSymbolKind.PROPERTY,
                null,
                CanonicalCompilerSignature.localProperty(
                    address,
                    returnType.toString(),
                    if (isVal) LocalPropertyMutability.VAL else LocalPropertyMutability.VAR,
                ),
            )
        else -> IntellijCompilerProjectionResult.Unsupported
    }
