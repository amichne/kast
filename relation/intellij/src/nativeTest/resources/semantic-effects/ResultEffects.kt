@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package effects.result

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

fun <T> selectedOnly(selected: () -> T, ignored: () -> T): T {
    contract {
        callsInPlace(selected, InvocationKind.EXACTLY_ONCE)
        callsInPlace(ignored, InvocationKind.EXACTLY_ONCE)
        returnsResultOf(selected)
    }
    ignored()
    return selected()
}

fun <T> choose(take: Boolean, fallback: T, selected: () -> T): T {
    contract {
        callsInPlace(selected, InvocationKind.AT_MOST_ONCE)
        returnsResultOf(selected)
    }
    return if (take) selected() else fallback
}

fun <T> discard(fallback: T, selected: () -> T): T {
    contract { callsInPlace(selected, InvocationKind.EXACTLY_ONCE) }
    selected()
    return fallback
}
