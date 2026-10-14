@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package effects

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

fun exactlyOnce(marker: Int, block: () -> Unit) {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    marker.hashCode()
    block()
}

fun atMostOnce(marker: Int, block: () -> Unit) {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
    if (marker > 0) block()
}

fun atLeastOnce(marker: Int, block: () -> Unit) {
    contract { callsInPlace(block, InvocationKind.AT_LEAST_ONCE) }
    block()
    repeat(marker.coerceAtLeast(0)) { block() }
}

fun unknown(marker: Int, block: () -> Unit) {
    contract { callsInPlace(block, InvocationKind.UNKNOWN) }
    repeat(marker.coerceAtLeast(0)) { block() }
}

// Same-name overloads supply no contract. Marker type selects the admitted declaration.
fun exactlyOnce(marker: String, block: () -> Unit) {
    if (marker.isNotEmpty()) block()
}

fun atMostOnce(marker: String, block: () -> Unit) {
    if (marker.isNotEmpty()) block()
}

fun atLeastOnce(marker: String, block: () -> Unit) {
    if (marker.isNotEmpty()) block()
}

fun unknown(marker: String, block: () -> Unit) {
    if (marker.isNotEmpty()) block()
}

fun absent(marker: Int, block: () -> Unit) {
    if (marker > 0) block()
}

fun otherParameter(marker: Int, declared: () -> Unit, block: () -> Unit) {
    contract { callsInPlace(declared, InvocationKind.EXACTLY_ONCE) }
    declared()
    if (marker > 0) block()
}
