package fixture.staticcallbacks.forwarding

import fixture.staticcallbacks.invocation.externalEscape
import fixture.staticcallbacks.invocation.invokeCallback

fun sharedWrapper(block: () -> String): String = forwardOnce(block)
fun forwardOnce(block: () -> String): String = invokeCallback(block)

fun recursiveWrapper(block: () -> String): String = recursiveWrapper(block)
fun selfInvokeWrapper(block: () -> String): String {
    block()
    return selfInvokeWrapper(block)
}
fun mutualFirst(block: () -> String): String = mutualSecond(block)
fun mutualSecond(block: () -> String): String {
    invokeCallback(block)
    return mutualFirst(block)
}
fun externalEscapeWrapper(block: () -> String): Int = externalEscape(block)
