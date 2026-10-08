@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignatureFailure
import io.github.amichne.kast.symbol.contract.CompilerDeclarationAddress
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import io.github.amichne.kast.symbol.contract.LocalPropertyMutability
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.localIdentityAdmitted
import io.github.amichne.kast.workspace.intellij.read.localIdentityRejected
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

internal fun KtNamedDeclaration.compilerEvidence(
    selector: SymbolSelector,
    observation: IntellijReadObservation,
): Refinement<CompilerGroundedSymbolEvidence, SourceCompilerEvidenceFailure> {
    val projection =
        when (val result = analyze(this) { symbol.sourceProjection(this, selector.file, observation = observation) }) {
            is SourceCompilerProjectionResult.Projected -> result.projection
            is SourceCompilerProjectionResult.LocalRejected -> {
                observation.localIdentityRejected(result.failure)
                return Refinement.Rejected(
                    when (result.failure) {
                        LocalDeclarationProjectionFailure.WorkLimitReached,
                        LocalDeclarationProjectionFailure.OwnerDepthExceeded ->
                            SourceCompilerEvidenceFailure.WORK_LIMIT_REACHED
                        LocalDeclarationProjectionFailure.CompilerTypeError,
                        LocalDeclarationProjectionFailure.CompilerTypeUnsupported,
                        LocalDeclarationProjectionFailure.UnsupportedDeclaration,
                        LocalDeclarationProjectionFailure.SignatureUnavailable,
                        LocalDeclarationProjectionFailure.SourceUnavailable,
                        LocalDeclarationProjectionFailure.OwnerUnavailable,
                        LocalDeclarationProjectionFailure.CompilerOwnerUnavailable,
                        LocalDeclarationProjectionFailure.LexicalAncestryUnavailable,
                        is LocalDeclarationProjectionFailure.InvalidAddress -> SourceCompilerEvidenceFailure.UNAVAILABLE
                    }
                )
            }
            SourceCompilerProjectionResult.Rejected ->
                return Refinement.Rejected(SourceCompilerEvidenceFailure.UNAVAILABLE)
        }
    return when (
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                file = selector.file,
                rawStartInclusive = textRange.startOffset,
                rawEndExclusive = textRange.endOffset,
                rawName = name.orEmpty(),
                rawQualifiedIdentity = projection.qualifiedIdentity,
                kind = projection.kind,
                signature = projection.signature,
            )
    ) {
        is Refinement.Refined -> {
            if (projection.signature.declarationAddress is CompilerDeclarationAddress.Local)
                observation.localIdentityAdmitted()
            Refinement.Refined(evidence.value)
        }
        is Refinement.Rejected -> {
            if (projection.signature.declarationAddress is CompilerDeclarationAddress.Local)
                observation.localIdentityRejected(LocalDeclarationProjectionFailure.SignatureUnavailable)
            Refinement.Rejected(SourceCompilerEvidenceFailure.UNAVAILABLE)
        }
    }
}

private data class SourceCompilerProjection(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: String?,
    val signature: CanonicalCompilerSignature,
)

private sealed interface SourceCompilerProjectionResult {
    data class Projected(val projection: SourceCompilerProjection) : SourceCompilerProjectionResult

    data class LocalRejected(val failure: LocalDeclarationProjectionFailure) : SourceCompilerProjectionResult

    data object Rejected : SourceCompilerProjectionResult
}

private fun KaSymbol.sourceProjection(
    session: KaSession,
    file: SymbolDiscoveryFileIdentity,
    depth: Int = 0,
    observation: IntellijReadObservation,
): SourceCompilerProjectionResult {
    if (depth > LocalDeclarationAddress.MAX_OWNER_DEPTH)
        return SourceCompilerProjectionResult.LocalRejected(LocalDeclarationProjectionFailure.OwnerDepthExceeded)
    if (location == KaSymbolLocation.LOCAL) return localSourceProjection(session, file, depth, observation)
    return qualifiedSourceProjection()
}

private fun KaSymbol.localSourceProjection(
    session: KaSession,
    file: SymbolDiscoveryFileIdentity,
    depth: Int,
    observation: IntellijReadObservation,
): SourceCompilerProjectionResult {
    if (!hasSupportedLocalDeclaration())
        return SourceCompilerProjectionResult.LocalRejected(LocalDeclarationProjectionFailure.UnsupportedDeclaration)
    when (val proof = localSourceCompilerTypeProof()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return SourceCompilerProjectionResult.LocalRejected(proof.failure)
    }
    val address =
        when (
            val admitted =
                localSourceDeclarationAddress(session, file, depth) { owner, ownerDepth ->
                    owner.localSourceOwnerSignature(session, file, ownerDepth, observation)
                }
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return SourceCompilerProjectionResult.LocalRejected(admitted.failure)
        }
    return when (val result = localSignatureProjection(address)) {
        is SourceCompilerProjectionResult.Projected -> result
        is SourceCompilerProjectionResult.LocalRejected -> result
        SourceCompilerProjectionResult.Rejected ->
            SourceCompilerProjectionResult.LocalRejected(LocalDeclarationProjectionFailure.SignatureUnavailable)
    }
}

private fun KaSymbol.hasSupportedLocalDeclaration(): Boolean {
    val declaration = psi
    return (this is KaNamedFunctionSymbol && declaration is KtNamedFunction && declaration.name != null) ||
        (this is KaLocalVariableSymbol && declaration is KtProperty && declaration.isLocal)
}

private fun KaSymbol.localSignatureProjection(address: LocalDeclarationAddress): SourceCompilerProjectionResult =
    when (this) {
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
        else -> SourceCompilerProjectionResult.Rejected
    }

private fun KaSymbol.localSourceOwnerSignature(
    session: KaSession,
    file: SymbolDiscoveryFileIdentity,
    depth: Int,
    observation: IntellijReadObservation,
): Refinement<CanonicalCompilerSignature, LocalDeclarationProjectionFailure> {
    if (depth > LocalDeclarationAddress.MAX_OWNER_DEPTH)
        return Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerDepthExceeded)
    if (location == KaSymbolLocation.LOCAL) return sourceProjection(session, file, depth, observation).ownerSignature()
    val projected =
        when (this) {
            is KaNamedFunctionSymbol -> {
                val identity =
                    when (val result = localOwnerCallableIdentity(session).observedLocalOwnerIdentity(observation)) {
                        is Refinement.Refined -> result.value.asString()
                        is Refinement.Rejected -> return result
                    }
                projected(CompilerSymbolKind.FUNCTION, identity, sourceFunctionSignature(identity))
            }
            is KaKotlinPropertySymbol -> {
                val identity =
                    when (val result = localOwnerCallableIdentity(session).observedLocalOwnerIdentity(observation)) {
                        is Refinement.Refined -> result.value.asString()
                        is Refinement.Rejected -> return result
                    }
                projected(CompilerSymbolKind.PROPERTY, identity, sourcePropertySignature(identity))
            }
            else -> qualifiedSourceProjection()
        }
    return projected.ownerSignature()
}

private fun SourceCompilerProjectionResult.ownerSignature():
    Refinement<CanonicalCompilerSignature, LocalDeclarationProjectionFailure> =
    when (this) {
        is SourceCompilerProjectionResult.Projected -> Refinement.Refined(projection.signature)
        is SourceCompilerProjectionResult.LocalRejected -> Refinement.Rejected(failure)
        SourceCompilerProjectionResult.Rejected ->
            Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerOwnerUnavailable)
    }

private fun KaSymbol.qualifiedSourceProjection(): SourceCompilerProjectionResult {
    return when (this) {
        is KaConstructorSymbol -> {
            val identity =
                containingClassId?.asSingleFqName()?.asString()?.let { "$it.<init>" }
                    ?: return SourceCompilerProjectionResult.Rejected
            projected(CompilerSymbolKind.CONSTRUCTOR, identity, sourceFunctionSignature(identity))
        }
        is KaFunctionSymbol -> {
            val identity = callableId?.asSingleFqName()?.asString() ?: return SourceCompilerProjectionResult.Rejected
            projected(CompilerSymbolKind.FUNCTION, identity, sourceFunctionSignature(identity))
        }
        is KaKotlinPropertySymbol -> {
            val identity = callableId?.asSingleFqName()?.asString() ?: return SourceCompilerProjectionResult.Rejected
            projected(
                CompilerSymbolKind.PROPERTY,
                identity,
                sourcePropertySignature(identity),
            )
        }
        is KaTypeAliasSymbol -> {
            val identity = classId?.asSingleFqName()?.asString() ?: return SourceCompilerProjectionResult.Rejected
            projected(
                CompilerSymbolKind.TYPE_ALIAS,
                identity,
                CanonicalCompilerSignature.typeAlias(identity),
            )
        }
        is KaClassLikeSymbol -> {
            val identity = classId?.asSingleFqName()?.asString() ?: return SourceCompilerProjectionResult.Rejected
            projected(
                CompilerSymbolKind.CLASSLIKE,
                identity,
                CanonicalCompilerSignature.classLike(identity),
            )
        }
        else -> SourceCompilerProjectionResult.Rejected
    }
}

private fun KaFunctionSymbol.sourceFunctionSignature(
    identity: String
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
    CanonicalCompilerSignature.function(
        identity,
        receiverParameter?.returnType?.toString(),
        contextReceivers.map { it.type.toString() },
        valueParameters.map { it.returnType.toString() },
        (this as? KaNamedFunctionSymbol)?.typeParameters?.size ?: 0,
    )

private fun KaKotlinPropertySymbol.sourcePropertySignature(
    identity: String
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
    CanonicalCompilerSignature.property(
        identity,
        receiverParameter?.returnType?.toString(),
        contextReceivers.map { it.type.toString() },
        returnType.toString(),
    )

private fun projected(
    kind: CompilerSymbolKind,
    identity: String?,
    signature: Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure>,
): SourceCompilerProjectionResult =
    when (signature) {
        is Refinement.Refined ->
            SourceCompilerProjectionResult.Projected(SourceCompilerProjection(kind, identity, signature.value))
        is Refinement.Rejected -> SourceCompilerProjectionResult.Rejected
    }
