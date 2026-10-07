package fixture.staticcallbacks.forwarding

import fixture.staticcallbacks.invocation.externalEscape
import fixture.staticcallbacks.invocation.invokeCallback

fun sharedWrapper(block: () -> String): String = forwardOnce(block)
fun forwardOnce(block: () -> String): String = invokeCallback(block)

fun recursiveWrapper(block: () -> String): String = recursiveWrapper(block)
fun externalEscapeWrapper(block: () -> String): Int = externalEscape(block)
