package fixture.staticcallbacks.invocation

fun invokeCallback(block: () -> String): String = block()
fun externalEscape(block: () -> String): Int = java.util.Collections.singletonList(block).size

fun alphaSink(): String = "alpha"
fun betaSink(): String = "beta"
fun referenceSink(): String = "reference"
fun recursiveSink(): String = "recursive"
fun selfRecursiveSink(): String = "self-recursive"
fun mutualRecursiveSink(): String = "mutual-recursive"
fun escapeSink(): String = "escape"
