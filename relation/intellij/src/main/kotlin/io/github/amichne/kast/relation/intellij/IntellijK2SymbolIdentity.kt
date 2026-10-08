@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignatureFailure
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol

internal data class IntellijCompilerProjection(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: String?,
    val signature: CanonicalCompilerSignature,
    val identity: CompilerSymbolIdentity,
)

internal sealed interface IntellijCompilerProjectionResult {
    data class Projected(val projection: IntellijCompilerProjection) : IntellijCompilerProjectionResult

    data class LocalRejected(val failure: LocalDeclarationProjectionFailure) : IntellijCompilerProjectionResult

    data object Unsupported : IntellijCompilerProjectionResult
}

internal enum class IntellijSymbolIdentityComparison {
    SAME,
    DIFFERENT,
    UNSUPPORTED,
}

/**
 * Proof transition: `KaSymbol -> IntellijCompilerProjectionResult`.
 *
 * A projected result establishes one closed symbol kind plus versioned, fixed-size, canonical-signature compiler
 * identity. Unsupported is the closed local/unavailable identity state. Raw K2 values remain inside the
 * analysis-session receiver.
 */
internal fun KaSymbol.compilerProjection(
    session: KaSession,
    file: SymbolDiscoveryFileIdentity? = null,
    depth: Int = 0,
    observation: IntellijReadObservation = IntellijReadObservation.None,
): IntellijCompilerProjectionResult {
    if (depth > LocalDeclarationAddress.MAX_OWNER_DEPTH)
        return IntellijCompilerProjectionResult.LocalRejected(LocalDeclarationProjectionFailure.OwnerDepthExceeded)
    if (location == KaSymbolLocation.LOCAL) return localRelationProjection(session, file, depth, observation)
    return when (this) {
        is KaValueParameterSymbol ->
            generatedPrimaryConstructorProperty?.compilerProjection(session, file, depth, observation)
                ?: IntellijCompilerProjectionResult.Unsupported
        is KaConstructorSymbol -> {
            val owner =
                containingClassId?.asSingleFqName()?.asString() ?: return IntellijCompilerProjectionResult.Unsupported
            projected(
                CompilerSymbolKind.CONSTRUCTOR,
                "$owner.<init>",
                functionSignature("$owner.<init>"),
            )
        }
        is KaFunctionSymbol -> projectedFunction()
        is KaKotlinPropertySymbol -> projectedProperty()
        is KaTypeAliasSymbol -> {
            val className = classId?.asSingleFqName()?.asString() ?: return IntellijCompilerProjectionResult.Unsupported
            projected(
                CompilerSymbolKind.TYPE_ALIAS,
                className,
                CanonicalCompilerSignature.typeAlias(className),
            )
        }
        is KaClassLikeSymbol -> {
            // Anonymous object literals have no stable classId. A source range alone cannot be reused as an exact
            // relation endpoint, so the provider must report incomplete coverage for these candidates.
            val className = classId?.asSingleFqName()?.asString() ?: return IntellijCompilerProjectionResult.Unsupported
            projected(
                CompilerSymbolKind.CLASSLIKE,
                className,
                CanonicalCompilerSignature.classLike(className),
            )
        }
        else -> IntellijCompilerProjectionResult.Unsupported
    }
}

private fun KaFunctionSymbol.projectedFunction(): IntellijCompilerProjectionResult {
    val callable = callableId?.asSingleFqName()?.asString() ?: return IntellijCompilerProjectionResult.Unsupported
    return projected(CompilerSymbolKind.FUNCTION, callable, functionSignature(callable))
}

/**
 * Proof transition: `(KaSymbol, KaSymbol) -> IntellijSymbolIdentityComparison`.
 *
 * SAME establishes identical detached compiler identities. DIFFERENT and UNSUPPORTED are closed non-admission states;
 * no PSI name, offset, or display text substitutes for K2 identity.
 */
internal fun KaSymbol.compareIdentity(other: KaSymbol, session: KaSession): IntellijSymbolIdentityComparison {
    val left =
        when (val result = compilerProjection(session)) {
            is IntellijCompilerProjectionResult.Projected -> result.projection.identity
            is IntellijCompilerProjectionResult.LocalRejected,
            IntellijCompilerProjectionResult.Unsupported -> return IntellijSymbolIdentityComparison.UNSUPPORTED
        }
    val right =
        when (val result = other.compilerProjection(session)) {
            is IntellijCompilerProjectionResult.Projected -> result.projection.identity
            is IntellijCompilerProjectionResult.LocalRejected,
            IntellijCompilerProjectionResult.Unsupported -> return IntellijSymbolIdentityComparison.UNSUPPORTED
        }
    return if (left == right) {
        IntellijSymbolIdentityComparison.SAME
    } else {
        IntellijSymbolIdentityComparison.DIFFERENT
    }
}

internal fun KaFunctionSymbol.functionSignature(
    callable: String
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
    CanonicalCompilerSignature.function(
        rawQualifiedIdentity = callable,
        rawReceiverType = receiverParameter?.returnType?.toString(),
        rawContextReceiverTypes = contextReceivers.map { it.type.toString() },
        rawValueParameterTypes = valueParameters.map { it.returnType.toString() },
        rawTypeParameterCount = (this as? KaNamedFunctionSymbol)?.typeParameters?.size ?: 0,
    )

internal fun projected(
    kind: CompilerSymbolKind,
    qualifiedIdentity: String?,
    signature: Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure>,
): IntellijCompilerProjectionResult =
    when (signature) {
        is Refinement.Refined ->
            IntellijCompilerProjectionResult.Projected(
                IntellijCompilerProjection(
                    kind,
                    qualifiedIdentity,
                    signature.value,
                    CompilerSymbolIdentity.fromCanonicalSignature(signature.value),
                )
            )
        is Refinement.Rejected -> IntellijCompilerProjectionResult.Unsupported
    }

private fun KaKotlinPropertySymbol.projectedProperty(): IntellijCompilerProjectionResult {
    val callable = callableId?.asSingleFqName()?.asString() ?: return IntellijCompilerProjectionResult.Unsupported
    return projected(
        CompilerSymbolKind.PROPERTY,
        callable,
        propertySignature(callable),
    )
}

internal fun KaKotlinPropertySymbol.propertySignature(
    callable: String
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
    CanonicalCompilerSignature.property(
        rawQualifiedIdentity = callable,
        rawReceiverType = receiverParameter?.returnType?.toString(),
        rawContextReceiverTypes = contextReceivers.map { it.type.toString() },
        rawReturnType = returnType.toString(),
    )
