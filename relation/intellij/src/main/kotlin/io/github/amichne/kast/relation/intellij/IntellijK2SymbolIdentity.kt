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
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol

internal data class IntellijCompilerProjection(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: String,
    val signature: CanonicalCompilerSignature,
    val identity: CompilerSymbolIdentity,
)

internal sealed interface IntellijCompilerProjectionResult {
    data class Projected(
        val projection: IntellijCompilerProjection,
    ) : IntellijCompilerProjectionResult

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
 * A projected result establishes one closed symbol kind plus versioned, fixed-size,
 * canonical-signature compiler identity. Unsupported is the closed local/unavailable identity
 * state. Raw K2 values remain inside the analysis-session receiver.
 */
internal fun KaSymbol.compilerProjection(): IntellijCompilerProjectionResult = when (this) {
    is KaConstructorSymbol -> {
        val owner = containingClassId?.asSingleFqName()?.asString()
                    ?: return IntellijCompilerProjectionResult.Unsupported
        projected(
            CompilerSymbolKind.CONSTRUCTOR,
            "$owner.<init>",
            functionSignature("$owner.<init>"),
        )
    }
    is KaFunctionSymbol -> {
        val callable = callableId?.asSingleFqName()?.asString()
                       ?: return IntellijCompilerProjectionResult.Unsupported
        projected(CompilerSymbolKind.FUNCTION, callable, functionSignature(callable))
    }
    is KaKotlinPropertySymbol -> {
        val callable = callableId?.asSingleFqName()?.asString()
                       ?: return IntellijCompilerProjectionResult.Unsupported
        projected(
            CompilerSymbolKind.PROPERTY,
            callable,
            CanonicalCompilerSignature.property(
                rawQualifiedIdentity = callable,
                rawReceiverType = receiverParameter?.returnType?.toString(),
                rawContextReceiverTypes = contextReceivers.map { it.type.toString() },
                rawReturnType = returnType.toString(),
            ),
        )
    }
    is KaTypeAliasSymbol -> {
        val className = classId?.asSingleFqName()?.asString()
                        ?: return IntellijCompilerProjectionResult.Unsupported
        projected(
            CompilerSymbolKind.TYPE_ALIAS,
            className,
            CanonicalCompilerSignature.typeAlias(className),
        )
    }
    is KaClassLikeSymbol -> {
        val className = classId?.asSingleFqName()?.asString()
                        ?: return IntellijCompilerProjectionResult.Unsupported
        projected(
            CompilerSymbolKind.CLASSLIKE,
            className,
            CanonicalCompilerSignature.classLike(className),
        )
    }
    else -> IntellijCompilerProjectionResult.Unsupported
}

/**
 * Proof transition: `(KaSymbol, KaSymbol) -> IntellijSymbolIdentityComparison`.
 *
 * SAME establishes identical detached compiler identities. DIFFERENT and UNSUPPORTED are closed
 * non-admission states; no PSI name, offset, or display text substitutes for K2 identity.
 */
internal fun KaSymbol.compareIdentity(other: KaSymbol): IntellijSymbolIdentityComparison {
    val left = when (val result = compilerProjection()) {
        is IntellijCompilerProjectionResult.Projected -> result.projection.identity
        IntellijCompilerProjectionResult.Unsupported ->
            return IntellijSymbolIdentityComparison.UNSUPPORTED
    }
    val right = when (val result = other.compilerProjection()) {
        is IntellijCompilerProjectionResult.Projected -> result.projection.identity
        IntellijCompilerProjectionResult.Unsupported ->
            return IntellijSymbolIdentityComparison.UNSUPPORTED
    }
    return if (left == right) {
        IntellijSymbolIdentityComparison.SAME
    } else {
        IntellijSymbolIdentityComparison.DIFFERENT
    }
}

private fun KaFunctionSymbol.functionSignature(
    callable: String,
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
    CanonicalCompilerSignature.function(
        rawQualifiedIdentity = callable,
        rawReceiverType = receiverParameter?.returnType?.toString(),
        rawContextReceiverTypes = contextReceivers.map { it.type.toString() },
        rawValueParameterTypes = valueParameters.map { it.returnType.toString() },
        rawTypeParameterCount = (this as? KaNamedFunctionSymbol)?.typeParameters?.size ?: 0,
    )

private fun projected(
    kind: CompilerSymbolKind,
    qualifiedIdentity: String,
    signature: Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure>,
): IntellijCompilerProjectionResult = when (signature) {
    is Refinement.Refined -> IntellijCompilerProjectionResult.Projected(
        IntellijCompilerProjection(
            kind,
            qualifiedIdentity,
            signature.value,
            CompilerSymbolIdentity.fromCanonicalSignature(signature.value),
        ),
    )
    is Refinement.Rejected -> IntellijCompilerProjectionResult.Unsupported
}
