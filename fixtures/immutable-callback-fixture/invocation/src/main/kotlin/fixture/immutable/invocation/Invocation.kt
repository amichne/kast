package fixture.immutable.invocation

fun alphaTarget(): String = "alpha"
fun betaTarget(): String = "beta"
fun invokeCallback(block: () -> String): String = block()
fun invokeReceiver(receiver: Receiver, block: (Receiver) -> String): String = block(receiver)
fun <T> invokeGeneric(value: T, block: (T) -> String): String = block(value)
class Receiver(val label: String) {
    fun member(): String = label
}
open class Base {
    open fun target(): String = "base"
}
class Derived : Base()
