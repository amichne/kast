@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignatureFailure
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal sealed interface IntellijCompilerSymbolLookupResult {
    data class Found(
        val evidence: CompilerGroundedSymbolEvidence,
    ) : IntellijCompilerSymbolLookupResult

    data class Rejected(
        val reason: IntellijSymbolSelectorRejection,
    ) : IntellijCompilerSymbolLookupResult
}

internal fun interface IntellijCompilerSymbolLookup {
    /**
     * Proof transition: `(CompiledIntellijSearchScope, IntellijExactDeclarationLookupKey) ->
     * IntellijCompilerSymbolLookupResult`.
     *
     * A found result establishes one scope-contained Kotlin declaration resolved to a detached K2
     * compiler identity. [IntellijSymbolSelectorRejection] is the closed expected failure. Live
     * PSI, K2 symbols, files, and scopes remain inside this request-local call.
     */
    fun find(
        compiledScope: CompiledIntellijSearchScope,
        key: IntellijExactDeclarationLookupKey,
    ): IntellijCompilerSymbolLookupResult
}

/** Request-local K2 exact-symbol lookup; no analysis-session value crosses [find]. */
internal class IntellijKotlinCompilerSymbolLookup(
    private val psiLookup: IntellijPsiExactDeclarationLookup,
) : IntellijCompilerSymbolLookup {
    /**
     * Proof transition: `(CompiledIntellijSearchScope, IntellijExactDeclarationLookupKey) ->
     * IntellijCompilerSymbolLookupResult`.
     *
     * Establishes that exact scope/name/offset PSI resolution produced one [KtNamedDeclaration],
     * then K2 analysis produced a closed symbol kind, qualified identity state, and overload-aware
     * compiler identity. [IntellijSymbolSelectorRejection] is the closed expected failure. Raw K2
     * values are detached before the analysis session ends.
     */
    override fun find(
        compiledScope: CompiledIntellijSearchScope,
        key: IntellijExactDeclarationLookupKey,
    ): IntellijCompilerSymbolLookupResult {
        val live = when (val lookup = psiLookup.findLive(compiledScope, key)) {
            is IntellijLiveExactDeclarationLookupResult.Found -> lookup
            is IntellijLiveExactDeclarationLookupResult.Rejected ->
                return IntellijCompilerSymbolLookupResult.Rejected(
                    lookup.reason.toSymbolSelectorRejection(),
                )
        }
        val declaration = live.declaration as? KtNamedDeclaration
                          ?: return rejected(
                              IntellijSymbolSelectorRejection.UNSUPPORTED_DECLARATION,
                          )
        val projection = when (val result = analyze(declaration) {
            declaration.symbol.toCompilerProjection()
        }) {
            is IntellijCompilerSymbolProjectionResult.Projected -> result.projection
            is IntellijCompilerSymbolProjectionResult.Rejected ->
                return rejected(result.reason)
        }
        return when (
            val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
                file = key.file,
                rawStartInclusive = declaration.textRange.startOffset,
                rawEndExclusive = declaration.textRange.endOffset,
                rawName = declaration.name.orEmpty(),
                rawQualifiedIdentity = projection.qualifiedIdentity,
                kind = projection.kind,
                signature = projection.signature,
            )
        ) {
            is Refinement.Refined -> IntellijCompilerSymbolLookupResult.Found(evidence.value)
            is Refinement.Rejected -> rejected(
                IntellijSymbolSelectorRejection.INTERNAL_INVARIANT,
            )
        }
    }
}

private data class IntellijCompilerSymbolProjection(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: String,
    val signature: CanonicalCompilerSignature,
)

private sealed interface IntellijCompilerSymbolProjectionResult {
    data class Projected(
        val projection: IntellijCompilerSymbolProjection,
    ) : IntellijCompilerSymbolProjectionResult

    data class Rejected(
        val reason: IntellijSymbolSelectorRejection,
    ) : IntellijCompilerSymbolProjectionResult
}

/**
 * Proof transition: `KaSymbol -> IntellijCompilerSymbolProjectionResult`.
 *
 * A projected result establishes a closed public kind plus versioned, fixed-size,
 * canonical-signature compiler identity. Rejection is the closed
 * [IntellijSymbolSelectorRejection.COMPILER_IDENTITY_UNAVAILABLE] state. Raw K2 values remain
 * inside the analysis-session receiver.
 */
private fun KaSymbol.toCompilerProjection(): IntellijCompilerSymbolProjectionResult {
    return when (this) {
        is KaConstructorSymbol -> {
            val owner = containingClassId?.asSingleFqName()?.asString()
                        ?: return compilerProjectionRejected()
            projected(
                CompilerSymbolKind.CONSTRUCTOR,
                "$owner.<init>",
                functionSignature("$owner.<init>"),
            )
        }
        is KaFunctionSymbol -> {
            val callable = callableId?.asSingleFqName()?.asString()
                           ?: return compilerProjectionRejected()
            projected(
                CompilerSymbolKind.FUNCTION,
                callable,
                functionSignature(callable),
            )
        }
        is KaKotlinPropertySymbol -> {
            val callable = callableId?.asSingleFqName()?.asString()
                           ?: return compilerProjectionRejected()
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
                            ?: return compilerProjectionRejected()
            projected(
                CompilerSymbolKind.TYPE_ALIAS,
                className,
                CanonicalCompilerSignature.typeAlias(className),
            )
        }
        is KaClassLikeSymbol -> {
            val className = classId?.asSingleFqName()?.asString()
                            ?: return compilerProjectionRejected()
            projected(
                CompilerSymbolKind.CLASSLIKE,
                className,
                CanonicalCompilerSignature.classLike(className),
            )
        }
        else -> compilerProjectionRejected()
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
): IntellijCompilerSymbolProjectionResult = when (signature) {
    is Refinement.Refined -> IntellijCompilerSymbolProjectionResult.Projected(
        IntellijCompilerSymbolProjection(
            kind,
            qualifiedIdentity,
            signature.value,
        ),
    )
    is Refinement.Rejected -> compilerProjectionRejected()
}

private fun rejected(
    reason: IntellijSymbolSelectorRejection,
): IntellijCompilerSymbolLookupResult.Rejected =
    IntellijCompilerSymbolLookupResult.Rejected(reason)

private fun compilerProjectionRejected(): IntellijCompilerSymbolProjectionResult.Rejected =
    IntellijCompilerSymbolProjectionResult.Rejected(
        IntellijSymbolSelectorRejection.COMPILER_IDENTITY_UNAVAILABLE,
    )

private fun IntellijExactDeclarationLookupRejection.toSymbolSelectorRejection():
    IntellijSymbolSelectorRejection = when (this) {
    IntellijExactDeclarationLookupRejection.STALE_LOCATION ->
        IntellijSymbolSelectorRejection.STALE_LOCATION
    IntellijExactDeclarationLookupRejection.OUTSIDE_SCOPE ->
        IntellijSymbolSelectorRejection.OUTSIDE_SCOPE
    IntellijExactDeclarationLookupRejection.AMBIGUOUS_DECLARATION ->
        IntellijSymbolSelectorRejection.AMBIGUOUS_DECLARATION
    IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION ->
        IntellijSymbolSelectorRejection.UNSUPPORTED_DECLARATION
}
