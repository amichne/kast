package fixture.controls

fun nullableInputKeepsSelectedField(): List<ScopeEnvelope> =
    /*<destination>*/ /*<input>*/listOf("left", "right")
        .map { ScopeEnvelope(scopePayload(), siblingPayload()) }/*</input>*/
        .mapNotNull { it }/*</destination>*/

fun nullableInputCannotCrossConstantReturn(): List<ScopeEnvelope> =
    /*<destination>*/ /*<input>*/listOf("left", "right")
        .map { ScopeEnvelope(scopePayload(), siblingPayload()) }/*</input>*/
        .mapNotNull { ScopeEnvelope(siblingPayload(), siblingPayload()) }/*</destination>*/

fun nullableInputCannotCrossNullReturn(): List<ScopeEnvelope> =
    /*<destination>*/ /*<input>*/listOf("left", "right")
        .map { ScopeEnvelope(scopePayload(), siblingPayload()) }/*</input>*/
        .mapNotNull { null }/*</destination>*/
