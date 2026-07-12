@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
package io.github.amichne.kast.idea.proofloss

import io.github.amichne.kast.shared.proofloss.model.*
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtNamedFunction

internal fun KtCallElement.toProofCallableKey(): ProofCallableKey? = analyze(this) {
    val functionCall = this@toProofCallableKey.resolveToCall()?.singleFunctionCallOrNull() ?: return@analyze null
    val resolved: KaFunctionSymbol = functionCall.partiallyAppliedSymbol.signature.symbol
    resolved.toProofCallableKey()
}

internal fun KtNamedFunction.toProofCallableKey(): ProofCallableKey? = analyze(this) {
    this@toProofCallableKey.symbol.toProofCallableKey()
}

/** Converts a resolved K2 identity to lifetime-independent declaration-backed data. */
private fun KaFunctionSymbol.toProofCallableKey(): ProofCallableKey? {
    val declaration = psi as? KtNamedFunction ?: return null
    val callableId = callableId?.asSingleFqName()?.asString()
        ?.let { (CallableIdKey.parse(it) as? ProofTextParseResult.Valid)?.value } ?: return null
    fun typeKey(text: String): KotlinTypeKey? = (KotlinTypeKey.parse(text) as? ProofTextParseResult.Valid)?.value
    return ProofCallableKey(
        callableId = callableId,
        kind = ProofCallableKind.FUNCTION,
        receiverType = declaration.receiverTypeReference?.text?.let(::typeKey),
        contextParameterTypes = contextReceivers.mapNotNull { receiver -> typeKey(receiver.type.toString()) },
        valueParameterTypes = declaration.valueParameters.map { parameter ->
            typeKey(parameter.typeReference?.text ?: return null) ?: return null
        },
        genericArity = declaration.typeParameters.size,
    )
}
