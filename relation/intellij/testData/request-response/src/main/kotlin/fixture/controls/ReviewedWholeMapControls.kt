package fixture.controls

fun wholeMapSource(): Map<ScopePayload, ScopePayload> = mapOf(siblingPayload() to scopePayload())

fun wholeMapValueContent(): Map<ScopePayload, ScopePayload> {
    val input = /*<input>*/wholeMapSource()/*</input>*/
    return /*<destination>*/input.mapValues { entry -> /*<entry-read>*/entry.value/*</entry-read>*/ }/*</destination>*/
}

fun wholeMapConstantValue(): Map<ScopePayload, ScopePayload> {
    val input = /*<input>*/wholeMapSource()/*</input>*/
    return /*<destination>*/input.mapValues { siblingPayload() }/*</destination>*/
}
