@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignatureFailure
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal fun KtNamedDeclaration.compilerEvidence(selector: SymbolSelector): CompilerGroundedSymbolEvidence? {
    val projection =
        when (val result = analyze(this) { symbol.sourceProjection() }) {
            is SourceCompilerProjectionResult.Projected -> result.projection
            SourceCompilerProjectionResult.Rejected -> return null
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
        is Refinement.Refined -> evidence.value
        is Refinement.Rejected -> null
    }
}

private data class SourceCompilerProjection(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: String,
    val signature: CanonicalCompilerSignature,
)

private sealed interface SourceCompilerProjectionResult {
    data class Projected(val projection: SourceCompilerProjection) : SourceCompilerProjectionResult

    data object Rejected : SourceCompilerProjectionResult
}

private fun KaSymbol.sourceProjection(): SourceCompilerProjectionResult =
    when (this) {
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
                CanonicalCompilerSignature.property(
                    identity,
                    receiverParameter?.returnType?.toString(),
                    contextReceivers.map { it.type.toString() },
                    returnType.toString(),
                ),
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

private fun projected(
    kind: CompilerSymbolKind,
    identity: String,
    signature: Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure>,
): SourceCompilerProjectionResult =
    when (signature) {
        is Refinement.Refined ->
            SourceCompilerProjectionResult.Projected(SourceCompilerProjection(kind, identity, signature.value))
        is Refinement.Rejected -> SourceCompilerProjectionResult.Rejected
    }
