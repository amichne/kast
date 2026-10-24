package fixture.controls

// Minimal equivalents of the report's shapes, not the unavailable original Probe.kt.
// The supplementary character keeps marker offsets honest about UTF-16: 🧭.
data class AtomicPacket(val text: String)

class Forwarder {
    fun forward(): AtomicPacket = /*<member-seed>*/AtomicPacket("member")/*</member-seed>*/
}

fun Forwarder.forwardExtension(): AtomicPacket =
    /*<extension-seed>*/AtomicPacket("extension")/*</extension-seed>*/

fun memberCaller(forwarder: Forwarder): AtomicPacket =
    /*<member-call>*/forwarder.forward()/*</member-call>*/

fun extensionCaller(forwarder: Forwarder): AtomicPacket =
    /*<extension-call>*/forwarder.forwardExtension()/*</extension-call>*/

fun consumeAtomic(packet: AtomicPacket): Unit = Unit

fun mutableCase() {
    var packet = /*<mutable-seed>*/AtomicPacket("mutable")/*</mutable-seed>*/
    consumeAtomic(packet)
}
