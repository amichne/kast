package fixture.controls

fun unknownAssociationKey(): String = "right"

fun spreadKeyAssociation(): Map<String, ScopePayload> =
    /*<destination>*/listOf(*arrayOf("left", "right")).associate { it to scopePayload() }/*</destination>*/

fun unknownLiteralKeyAssociation(): Map<String, ScopePayload> =
    /*<destination>*/listOf("left", unknownAssociationKey()).associate { it to scopePayload() }/*</destination>*/

fun singleOverloadKeyAssociation(): Map<String, ScopePayload> =
    /*<destination>*/listOf("only").associate { it to scopePayload() }/*</destination>*/

fun transformedKeyAssociation(): Map<String, ScopePayload> =
    /*<destination>*/listOf("left", "right").associate { it.lowercase() to scopePayload() }/*</destination>*/

fun capturedKeyAssociation(): Map<String, ScopePayload> {
    val key = "fixed"
    return /*<destination>*/listOf("left", "right").associate { key to scopePayload() }/*</destination>*/
}

fun aliasKeyAssociation(): Map<String, ScopePayload> =
    /*<destination>*/listOf("left", "right").associate {
        val key = it
        key to scopePayload()
    }/*</destination>*/
