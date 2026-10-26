@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package fixture.controls.returnsresult

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class Payload

fun produced(): Payload = Payload()

fun preserve(block: () -> Payload): Payload {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        returnsResultOf(block)
    }
    return block()
}

fun choose(take: Boolean, fallback: Payload, block: () -> Payload): Payload {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
        returnsResultOf(block)
    }
    return if (take) block() else /*<fallback>*/fallback/*</fallback>*/
}

fun discard(block: () -> Payload): Payload {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    block()
    return Payload()
}

fun selectedOnly(selected: () -> Payload, ignored: () -> Payload): Payload {
    contract {
        callsInPlace(selected, InvocationKind.EXACTLY_ONCE)
        callsInPlace(ignored, InvocationKind.EXACTLY_ONCE)
        returnsResultOf(selected)
    }
    /*<ignored-call>*/ignored()/*</ignored-call>*/
    val result = selected()
    return /*<selected-result>*/result/*</selected-result>*/
}

fun dormant(block: () -> Unit) {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
}

fun twice(block: () -> Unit) {
    contract { callsInPlace(block, InvocationKind.AT_LEAST_ONCE) }
    block()
    block()
}

fun keptPayload(): Payload {
    val output = preserve { produced() }
    return /*<output>*/output/*</output>*/
}

fun ignoredPayload(): Payload {
    val output = discard { produced() }
    return /*<output>*/output/*</output>*/
}

fun callbackChoice(take: Boolean): Payload {
    val output = choose(take, Payload()) { produced() }
    return /*<output>*/output/*</output>*/
}

fun fallbackChoice(take: Boolean): Payload {
    val fallback = produced()
    val output = choose(take, fallback) { Payload() }
    return /*<output>*/output/*</output>*/
}

fun selectedCallbackPayload(): Payload {
    val output = selectedOnly(selected = { produced() }, ignored = { Payload() })
    return /*<output>*/output/*</output>*/
}

fun ignoredCallbackPayload(): Payload {
    val output = selectedOnly(selected = { Payload() }, ignored = { produced() })
    return /*<output>*/output/*</output>*/
}
