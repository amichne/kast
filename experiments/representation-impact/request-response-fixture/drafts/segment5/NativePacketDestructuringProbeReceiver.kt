@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaReceiverParameterSymbol

internal enum class DestructuringReceiverSlot {
    EXTENSION,
    DISPATCH,
}

/** Each receiver keeps its actual K2 shape. A smart cast exposes one original receiver without recursive traversal. */
internal fun KaSession.recordDestructuringReceiver(
    slot: DestructuringReceiverSlot,
    receiver: KaReceiverValue?,
    anonymous: KaAnonymousFunctionSymbol,
) {
    when (receiver) {
        null -> println("DESTRUCTURING_RECEIVER slot=$slot kind=NONE")
        is KaExplicitReceiverValue ->
            println(
                "DESTRUCTURING_RECEIVER slot=$slot kind=EXPLICIT range=${range(receiver.expression)} " +
                    "diagnosticType=${receiver.type}"
            )
        is KaImplicitReceiverValue -> recordImplicit(slot, receiver, anonymous)
        is KaSmartCastedReceiverValue -> {
            println("DESTRUCTURING_RECEIVER slot=$slot kind=SMART_CAST diagnosticType=${receiver.type}")
            when (val original = receiver.original) {
                is KaExplicitReceiverValue ->
                    println("DESTRUCTURING_ORIGINAL slot=$slot kind=EXPLICIT range=${range(original.expression)}")
                is KaImplicitReceiverValue -> recordImplicit(slot, original, anonymous)
                else -> println("DESTRUCTURING_ORIGINAL slot=$slot kind=OTHER_OR_NESTED_SMART_CAST")
            }
        }
        else ->
            println(
                "DESTRUCTURING_RECEIVER slot=$slot kind=OTHER subtype=${receiver.javaClass.simpleName.take(MAX_NAME)}"
            )
    }
}

private fun KaSession.recordImplicit(
    slot: DestructuringReceiverSlot,
    receiver: KaImplicitReceiverValue,
    anonymous: KaAnonymousFunctionSymbol,
) {
    val symbol = receiver.symbol
    val owner = (symbol as? KaReceiverParameterSymbol)?.owningCallableSymbol
    println(
        "DESTRUCTURING_RECEIVER slot=$slot kind=IMPLICIT subtype=${symbol.javaClass.simpleName.take(MAX_NAME)} " +
            "psiRange=${symbol.psi?.let(::range)} sameAnonymous=${symbol == anonymous} " +
            "sameReceiver=${symbol == anonymous.receiverParameter} receiverOwnerIsAnonymous=${owner == anonymous} " +
            "origin=${symbol.origin} diagnosticType=${receiver.type}"
    )
    when (val projected = symbol.compilerProjection(this)) {
        is IntellijCompilerProjectionResult.Projected ->
            println(
                "DESTRUCTURING_RECEIVER_SYMBOL slot=$slot status=PROJECTED " +
                    "identity=${projected.projection.identity.value}"
            )
        is IntellijCompilerProjectionResult.LocalRejected ->
            println("DESTRUCTURING_RECEIVER_SYMBOL slot=$slot status=LOCAL_REJECTED cause=${projected.failure}")
        IntellijCompilerProjectionResult.Unsupported ->
            println("DESTRUCTURING_RECEIVER_SYMBOL slot=$slot status=UNSUPPORTED")
    }
}

private const val MAX_NAME = 256
