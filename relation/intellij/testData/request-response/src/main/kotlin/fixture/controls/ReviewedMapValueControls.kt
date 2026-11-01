package fixture.controls

fun reviewedInputMap(): Map<String, ScopePayload> = mapOf("input" to siblingPayload())

fun mapValueDiscardedProducer(): Map<String, ScopePayload> =
    /*<destination>*/reviewedInputMap().mapValues {
        scopePayload()
        siblingPayload()
    }/*</destination>*/

fun mapValueReturnedProducer(): Map<String, ScopeEnvelope> =
    /*<destination>*/reviewedInputMap().mapValues {
        ScopeEnvelope(scopePayload(), siblingPayload())
    }/*</destination>*/

fun mapValueNestedReturn(): Map<String, ScopeEnvelope> =
    /*<destination>*/reviewedInputMap().let /*<parent-body>*/{ values ->
        /*<mapped>*/values.mapValues { ScopeEnvelope(scopePayload(), siblingPayload()) }/*</mapped>*/
    }/*</parent-body>*//*</destination>*/

fun mapValueSiblingOwnerCannotReceiveProducer(): Map<String, ScopePayload> {
    reviewedInputMap().let { values ->
        /*<selected-map>*/values.mapValues { scopePayload() }/*</selected-map>*/
    }
    return /*<destination>*/reviewedInputMap().let { other ->
        /*<sibling-map>*/other.mapValues { siblingPayload() }/*</sibling-map>*/
    }/*</destination>*/
}
