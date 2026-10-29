package fixture.controls

fun groupSelectorCannotBecomeBucketElement(): Map<ScopePayload, List<ScopePayload>> =
    /*<destination>*/listOf("left", "right").map { siblingPayload() }.groupBy { scopePayload() }/*</destination>*/

fun singleCallbackGroupKeepsSelectedElement(): Map<ScopePayload, List<ScopeEnvelope>> =
    /*<destination>*/listOf("left", "right")
        .map { ScopeEnvelope(scopePayload(), siblingPayload()) }
        .groupBy { it.sibling }/*</destination>*/
