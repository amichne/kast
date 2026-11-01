@file:OptIn(kotlin.contracts.ExperimentalContracts::class, kotlin.contracts.ExperimentalExtendedContracts::class)

package fixture.controls.contractscope

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class Payload
sealed interface Choice
class Selected(val payload: Payload) : Choice
class Other : Choice

fun choose(condition: Boolean, selected: () -> Payload, other: () -> Unit): Payload {
    contract {
        condition holdsIn selected
        callsInPlace(selected, InvocationKind.AT_MOST_ONCE)
        callsInPlace(other, InvocationKind.EXACTLY_ONCE)
    }
    other()
    return if (condition) selected() else Payload()
}

fun observe(value: Choice) {}

fun scoped(input: Choice): Payload {
    val result = choose(input is Selected, { input.payload }, { observe(input) })
    observe(input)
    return result
}
