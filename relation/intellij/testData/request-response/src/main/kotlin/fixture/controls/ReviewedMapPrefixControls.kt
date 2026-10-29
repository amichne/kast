package fixture.controls

fun selectedKeyWithBothComponents(): Map<ScopePayload, ScopePayload> {
    val input = /*<input>*/listOf("left", "right").associate { scopePayload() to siblingPayload() }/*</input>*/
    return /*<destination>*/input.mapValues { (key, value) -> /*<entry-read>*/value/*</entry-read>*/ }/*</destination>*/
}

fun selectedValueWithBothComponents(): Map<ScopePayload, ScopePayload> {
    val input = /*<input>*/listOf("left", "right").associate { siblingPayload() to scopePayload() }/*</input>*/
    return /*<destination>*/input.mapValues { (key, value) -> /*<entry-read>*/value/*</entry-read>*/ }/*</destination>*/
}
