@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.proofloss

import io.github.amichne.kast.shared.proofloss.model.CallableIdKey
import io.github.amichne.kast.shared.proofloss.model.CallableKey
import io.github.amichne.kast.shared.proofloss.model.CallableKind
import io.github.amichne.kast.shared.proofloss.model.KotlinTypeKey
import io.github.amichne.kast.shared.proofloss.model.TextParseResult
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtNamedFunction

internal fun KtCallElement.toCallableKey(): CallableKey? = analyze(this) {
    this@toCallableKey.resolveToCall()
        ?.singleFunctionCallOrNull()
        ?.partiallyAppliedSymbol
        ?.signature
        ?.symbol
        ?.toCallableKey()
}

internal fun KtNamedFunction.toCallableKey(): CallableKey? = analyze(this) {
    this@toCallableKey.symbol.toCallableKey()
}

/** Converts a resolved K2 identity to lifetime-independent declaration-backed data. */
private fun KaFunctionSymbol.toCallableKey(): CallableKey? =
    (psi as? KtNamedFunction)?.let { declaration ->
        callableId
            ?.asSingleFqName()
            ?.asString()
            ?.toCallableIdKey()
            ?.let { callableId ->
                declaration.valueParameters
                    .map { it.typeReference?.text?.toKotlinTypeKey() }
                    .takeIf { null !in it }
                    ?.filterNotNull()
                    ?.let { valueParameterTypes ->
                        CallableKey(
                            callableId = callableId,
                            kind = CallableKind.FUNCTION,
                            receiverType = declaration.receiverTypeReference?.text?.toKotlinTypeKey(),
                            contextParameterTypes = contextReceivers
                                .map { it.type.toString().toKotlinTypeKey() }
                                .filterNotNull(),
                            valueParameterTypes = valueParameterTypes,
                            genericArity = declaration.typeParameters.size,
                        )
                    }
            }
    }

private fun String.toCallableIdKey(): CallableIdKey? =
    (CallableIdKey.parse(this) as? TextParseResult.Valid)?.value

private fun String.toKotlinTypeKey(): KotlinTypeKey? =
    (KotlinTypeKey.parse(this) as? TextParseResult.Valid)?.value
