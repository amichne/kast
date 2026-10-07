package fixture.staticcallbacks.suppliers

import fixture.staticcallbacks.forwarding.externalEscapeWrapper
import fixture.staticcallbacks.forwarding.recursiveWrapper
import fixture.staticcallbacks.forwarding.sharedWrapper
import fixture.staticcallbacks.invocation.alphaSink
import fixture.staticcallbacks.invocation.betaSink
import fixture.staticcallbacks.invocation.escapeSink
import fixture.staticcallbacks.invocation.recursiveSink
import fixture.staticcallbacks.invocation.referenceSink

// The suppliers share the formal route, but each owns a distinct callback body.
fun alphaEntry(): String {
    sharedWrapper { alphaSink() }
    return sharedWrapper { alphaSink(); "alpha" }
}
fun betaEntry(): String = sharedWrapper { betaSink() }

// Callable references remain outside the admitted COMPLETE_ONLY callback model.
fun callableReferenceEntry(): String = sharedWrapper(::referenceSink)

// Static negative controls: never execute these functions to qualify a query.
fun recursiveEntry(): String = recursiveWrapper { recursiveSink() }
fun externalEscapeEntry(): Int = externalEscapeWrapper { escapeSink() }
