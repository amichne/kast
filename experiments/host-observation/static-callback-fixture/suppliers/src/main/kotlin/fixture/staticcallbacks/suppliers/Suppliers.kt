package fixture.staticcallbacks.suppliers

import fixture.staticcallbacks.forwarding.externalEscapeWrapper
import fixture.staticcallbacks.forwarding.recursiveWrapper
import fixture.staticcallbacks.forwarding.selfInvokeWrapper
import fixture.staticcallbacks.forwarding.mutualFirst
import fixture.staticcallbacks.forwarding.sharedWrapper
import fixture.staticcallbacks.invocation.alphaSink
import fixture.staticcallbacks.invocation.betaSink
import fixture.staticcallbacks.invocation.escapeSink
import fixture.staticcallbacks.invocation.recursiveSink
import fixture.staticcallbacks.invocation.referenceSink
import fixture.staticcallbacks.invocation.selfRecursiveSink
import fixture.staticcallbacks.invocation.mutualRecursiveSink

// The suppliers share the formal route, but each owns a distinct callback body.
fun alphaEntry(): String {
    sharedWrapper { alphaSink() }
    return sharedWrapper { alphaSink(); "alpha" }
}
fun betaEntry(): String = sharedWrapper { betaSink() }

// Callable references remain outside the admitted COMPLETE_ONLY callback model.
fun callableReferenceEntry(): String = sharedWrapper(::referenceSink)

// Static recursive graphs: never execute these functions to qualify a query.
fun recursiveEntry(): String = recursiveWrapper { recursiveSink() }
fun selfRecursiveEntry(): String {
    selfInvokeWrapper { selfRecursiveSink() }
    return selfInvokeWrapper { selfRecursiveSink(); "self" }
}
fun mutualRecursiveEntry(): String = mutualFirst { mutualRecursiveSink() }

fun externalEscapeEntry(): Int = externalEscapeWrapper { escapeSink() }
