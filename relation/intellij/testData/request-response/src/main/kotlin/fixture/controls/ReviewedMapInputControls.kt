package fixture.controls

fun selectedKeyWithExplicitValue(): Map<ScopePayload, ScopePayload> {
    val input = /*<input>*/listOf("left", "right").associate { scopePayload() to siblingPayload() }/*</input>*/
    return /*<destination>*/input.mapValues { entry -> /*<entry-read>*/entry.value/*</entry-read>*/ }/*</destination>*/
}

fun selectedValueWithExplicitValue(): Map<ScopePayload, ScopePayload> {
    val input = /*<input>*/listOf("left", "right").associate { siblingPayload() to scopePayload() }/*</input>*/
    return /*<destination>*/input.mapValues { entry -> /*<entry-read>*/entry.value/*</entry-read>*/ }/*</destination>*/
}

fun selectedValueWithExplicitKey(): Map<ScopePayload, ScopePayload> {
    val input = /*<input>*/listOf("left", "right").associate { siblingPayload() to scopePayload() }/*</input>*/
    return /*<destination>*/input.mapValues { entry -> /*<entry-read>*/entry.key/*</entry-read>*/ }/*</destination>*/
}

fun selectedKeyWithExplicitKey(): Map<ScopePayload, ScopePayload> {
    val input = /*<input>*/listOf("left", "right").associate { scopePayload() to siblingPayload() }/*</input>*/
    return /*<destination>*/input.mapValues { entry -> /*<entry-read>*/entry.key/*</entry-read>*/ }/*</destination>*/
}

fun selectedKeyWithDestructuredValue(): Map<ScopePayload, ScopePayload> {
    val input = /*<input>*/listOf("left", "right").associate { scopePayload() to siblingPayload() }/*</input>*/
    return /*<destination>*/input.mapValues { (_, value) -> /*<entry-read>*/value/*</entry-read>*/ }/*</destination>*/
}

fun selectedValueWithDestructuredValue(): Map<ScopePayload, ScopePayload> {
    val input = /*<input>*/listOf("left", "right").associate { siblingPayload() to scopePayload() }/*</input>*/
    return /*<destination>*/input.mapValues { (_, value) -> /*<entry-read>*/value/*</entry-read>*/ }/*</destination>*/
}

fun selectedValueWithIgnoredEntry(): Map<ScopePayload, ScopePayload> {
    val input = /*<input>*/listOf("left", "right").associate { siblingPayload() to scopePayload() }/*</input>*/
    return /*<destination>*/input.mapValues { siblingPayload() }/*</destination>*/
}
