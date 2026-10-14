@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package controls

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

fun duplicate(block: () -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    block()
}
