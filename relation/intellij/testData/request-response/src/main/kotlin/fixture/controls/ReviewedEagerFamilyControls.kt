package fixture.controls

fun reviewedPredicate(): Boolean = true

fun predicateReturnCannotBecomeElement(): List<Boolean> =
    /*<destination>*/listOf(false, true).filter { reviewedPredicate() }/*</destination>*/

fun filteredSelectedContent(): List<ScopeEnvelope> =
    /*<destination>*/listOf("left", "right")
        .map { ScopeEnvelope(scopePayload(), siblingPayload()) }
        .filter { true }/*</destination>*/

fun nullResultDiscardsProducer(): List<ScopePayload> =
    /*<destination>*/listOf("left", "right").mapNotNull {
        scopePayload()
        null
    }/*</destination>*/

fun constantNonNullDiscardsProducer(): List<ScopePayload> =
    /*<destination>*/listOf("left", "right").mapNotNull {
        scopePayload()
        siblingPayload()
    }/*</destination>*/

fun nonNullSelectedContent(): List<ScopeEnvelope> =
    /*<destination>*/listOf("left", "right").mapNotNull {
        ScopeEnvelope(scopePayload(), siblingPayload())
    }/*</destination>*/

fun applyCallbackCannotReplaceReceiver(): ScopePayload =
    /*<destination>*/siblingPayload().apply { scopePayload() }/*</destination>*/

fun applyKeepsSelectedReceiver(): ScopePayload =
    /*<destination>*/scopePayload().apply { siblingPayload() }/*</destination>*/

fun transformedGroupCannotAcquireOriginal(): Map<String, List<ScopePayload>> =
    /*<destination>*/listOf("left", "right").map { scopePayload() }.groupBy(
        keySelector = { "same-key" },
        valueTransform = { siblingPayload() },
    )/*</destination>*/

fun transformedGroupKeepsSelectedResult(): Map<String, List<ScopeEnvelope>> =
    /*<destination>*/listOf("left", "right").groupBy(
        keySelector = { "same-key" },
        valueTransform = { ScopeEnvelope(scopePayload(), siblingPayload()) },
    )/*</destination>*/
