package fixture.controls

fun plainRunSelectedResult(): ScopeEnvelope =
    /*<destination>*/run { ScopeEnvelope(scopePayload(), siblingPayload()) }/*</destination>*/

fun receiverRunSelectedResult(): ScopeEnvelope =
    /*<destination>*/siblingPayload().run { ScopeEnvelope(scopePayload(), this) }/*</destination>*/

fun withSelectedResult(): ScopeEnvelope =
    /*<destination>*/with(siblingPayload()) { ScopeEnvelope(scopePayload(), this) }/*</destination>*/

fun plainRunDiscardsProducer(): ScopePayload =
    /*<destination>*/run { scopePayload(); siblingPayload() }/*</destination>*/

fun receiverRunDiscardsProducer(): ScopePayload =
    /*<destination>*/siblingPayload().run { scopePayload(); siblingPayload() }/*</destination>*/

fun withDiscardsProducer(): ScopePayload =
    /*<destination>*/with(siblingPayload()) { scopePayload(); siblingPayload() }/*</destination>*/

fun throwingRunCannotReturnProducer(): ScopePayload =
    /*<destination>*/run { scopePayload(); throw IllegalStateException("stop") }/*</destination>*/

fun throwingApplyCannotReturnReceiver(): ScopePayload =
    /*<destination>*/scopePayload().apply { throw IllegalStateException("stop") }/*</destination>*/

fun nonLocalApplyCannotReturnReceiver(): ScopePayload {
    return /*<destination>*/scopePayload().apply { return siblingPayload() }/*</destination>*/
}

class ReviewedScopeShadow(val selected: ScopePayload) {
    fun extensionReceiverKeepsItsOwnField(): ScopePayload =
        /*<destination>*/ReviewedScopeShadow(scopePayload()).run { this.selected }/*</destination>*/

    fun extensionReceiverCannotBecomeDispatchField(): ScopePayload =
        /*<destination>*/ReviewedScopeShadow(scopePayload()).run { this@ReviewedScopeShadow.selected }/*</destination>*/
}
