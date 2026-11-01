package fixture.controls

import effects.result.choose
import effects.result.discard
import effects.result.selectedOnly

fun binaryPayload(): String = "payload"

fun consumeBinary(value: String) {}

fun binarySelected() {
    val result = selectedOnly(ignored = { "other" }, selected = { binaryPayload() })
    val sibling = selectedOnly(ignored = { "other" }, selected = { "unrelated" })
    consumeBinary(/*<selected>*/result/*</selected>*/)
    consumeBinary(/*<sibling>*/sibling/*</sibling>*/)
}

fun binaryAlternative(take: Boolean) {
    val result = choose(take, "fallback") { binaryPayload() }
    consumeBinary(/*<selected>*/result/*</selected>*/)
}

fun binaryUnlisted() {
    val result = selectedOnly(selected = { "other" }, ignored = { binaryPayload() })
    consumeBinary(/*<selected>*/result/*</selected>*/)
}

fun binaryDiscarded() {
    val result = discard("fallback") { binaryPayload() }
    consumeBinary(/*<selected>*/result/*</selected>*/)
}
