package fixture.immutable.forwarding

import fixture.immutable.invocation.betaTarget
import fixture.immutable.invocation.alphaTarget
import fixture.immutable.invocation.invokeCallback

fun wrapper(block: () -> String): String = invokeCallback(block)
fun defaultWrapper(block: () -> String = ::alphaTarget): String = invokeCallback(block)
fun captureFactory(block: () -> String): () -> String = { block() }
fun choiceFactory(first: Boolean): () -> String =
    if (first) ::alphaTarget else ::betaTarget
fun neverInvoked(block: () -> String): String = "unused"
fun externalEscape(block: () -> String): Int = java.util.Collections.singletonList(block).size
fun inventoryWrapper(block: () -> String): String = block()
fun knownSuppliers(block: () -> String): String = block()
fun identityFactory(block: () -> String): () -> String = block
fun capturedAliasFactory(block: () -> String): () -> String {
    val saved = block
    return { saved() }
}
fun mutableCaptureFactory(block: () -> String): () -> String {
    var saved = block
    return { saved() }
}
fun forwardingCaptureFactory(block: () -> String): () -> String = { wrapper(block) }
class ReceiverFactory {
    fun create(block: () -> String): () -> String = block
}

object externalObject { val property: String get() = "external" }
fun getterFactory(): () -> String = { externalObject.property }
fun operatorFactory(block: () -> String, scalar: Int): () -> String = { scalar + 1; block() }
fun subjectfulWhenFactory(value: Any): () -> String = {
    when (value) { 1 -> betaTarget(); else -> betaTarget() }
}
