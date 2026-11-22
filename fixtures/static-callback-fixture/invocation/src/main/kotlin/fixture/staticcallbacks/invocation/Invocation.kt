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

class ReferenceReceiver {
    fun boundSink(): String = "bound"
    fun unboundSink(): String = "unbound"
}
fun invokeReceiverCallback(receiver: ReferenceReceiver, block: (ReferenceReceiver) -> String): String = block(receiver)

fun aliasSink(): String = "alias"
fun mutableAliasSink(): String = "mutable"
