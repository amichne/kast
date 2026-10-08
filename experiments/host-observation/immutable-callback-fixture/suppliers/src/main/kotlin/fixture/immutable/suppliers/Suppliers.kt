package fixture.immutable.suppliers

import fixture.immutable.forwarding.*
import fixture.immutable.invocation.*

fun referenceEntry(): String = wrapper(::alphaTarget)
fun boundReferenceEntry(receiver: Receiver): String = wrapper(receiver::member)
fun unboundReferenceEntry(receiver: Receiver): String = invokeReceiver(receiver, Receiver::member)
fun aliasEntry(): String {
    val callback = { alphaTarget() }
    val alias = callback
    return wrapper(alias)
}
fun anonymousEntry(): String = wrapper(fun(): String { return alphaTarget() })
fun defaultEntry(): String = defaultWrapper()
fun explicitDefaultEntry(): String = defaultWrapper(::betaTarget)
fun genericEntry(receiver: Receiver): String = invokeGeneric(receiver, Receiver::member)
fun capturedAlphaEntry(): String = wrapper(captureFactory(::alphaTarget))
fun capturedBetaEntry(): String = wrapper(captureFactory(::betaTarget))
fun branchEntry(first: Boolean): String = wrapper(choiceFactory(first))
fun storedNeverInvokedEntry(): String = neverInvoked(::alphaTarget)
fun mutableEntry(): String {
    var callback = ::alphaTarget
    callback = ::betaTarget
    return wrapper(callback)
}
fun externalEntry(): Int = externalEscape(::alphaTarget)
fun overload(value: Any): String = alphaTarget()
fun overloadEntry(): String = overload("text")
fun dispatchEntry(value: Base): String = value.target()
fun knownAlphaSupplier(): String = knownSuppliers(::alphaTarget)
fun knownBetaSupplier(): String = knownSuppliers(::betaTarget)

fun localReferenceEntry(): String {
    val first = ::alphaTarget
    val second = first
    return wrapper(second)
}
fun directBranchEntry(first: Boolean): String = wrapper(if (first) ::alphaTarget else ::betaTarget)
fun localBranchEntry(first: Boolean): String {
    val selected = if (first) ::alphaTarget else ::betaTarget
    return wrapper(selected)
}
fun identityReturnedEntry(): String = wrapper(identityFactory(::alphaTarget))
fun capturedAliasEntry(): String = wrapper(capturedAliasFactory(::betaTarget))
fun mutableCaptureEntry(): String = wrapper(mutableCaptureFactory(::alphaTarget))
fun forwardingCaptureEntry(): String = wrapper(forwardingCaptureFactory(::alphaTarget))
fun receiverFactoryEntry(factory: ReceiverFactory): String = wrapper(factory.create(::alphaTarget))
fun directReturnedEntry(): String = captureFactory(::alphaTarget)()
fun localReturnedEntry(): String {
    val action = captureFactory(::betaTarget)
    return action()
}

fun getterEntry(): String = wrapper(getterFactory())
fun operatorEntry(): String = wrapper(operatorFactory(::alphaTarget, 1))
fun subjectfulWhenEntry(): String = wrapper(subjectfulWhenFactory(1))

fun directTryEntry(): String = wrapper(try { ::alphaTarget } catch(e: Exception) { ::betaTarget })
fun localTryEntry(): String {
    val callback = try { ::alphaTarget } catch(e: Exception) { ::betaTarget }
    val alias = callback
    return wrapper(alias)
}
fun nestedTryEntry(): String = wrapper(
    try { try { ::alphaTarget } catch(e: IllegalStateException) { ::betaTarget } }
    catch(e: Exception) { ::betaTarget }
)
fun factoryTryEntry(): String = wrapper(tryChoiceFactory())
fun finallyTryEntry(): String = wrapper(try { ::alphaTarget } finally { betaTarget() })
fun finallyFactoryEntry(): String = wrapper(finallyOverrideFactory())
fun abruptTryEntry(): String = wrapper(abruptTryFactory())
