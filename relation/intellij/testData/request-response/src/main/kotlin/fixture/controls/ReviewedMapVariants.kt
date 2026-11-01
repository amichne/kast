package fixture.controls

fun emptyMapScopeItems(): List<String> = emptyList()

fun emptyMapSelectedRoute(): List<ScopeItem> =
    /*<destination>*/emptyMapScopeItems()
        .map { ScopeEnvelope(scopePayload(), siblingPayload()) }
        .map { ScopeItem(/*<body-read>*/it/*</body-read>*/.selected) }/*</destination>*/

fun artifactMapSelectedRoute(): List<ScopeItem> {
    val prepared = /*<first-output>*/mapScopeItems()
        .map { ScopeEnvelope(scopePayload(), siblingPayload()) }/*</first-output>*/
    return /*<destination>*/prepared.map { ScopeItem(it.selected) }/*</destination>*/
}
