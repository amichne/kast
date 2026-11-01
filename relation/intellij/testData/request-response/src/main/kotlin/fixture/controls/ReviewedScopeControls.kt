@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package fixture.controls

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

data class ScopePayload(val text: String)
data class ScopeEnvelope(val selected: ScopePayload, val sibling: ScopePayload)

fun scopePayload(): ScopePayload = ScopePayload("selected")
fun siblingPayload(): ScopePayload = ScopePayload("sibling")

inline fun discardScope(block: () -> ScopePayload): ScopePayload {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    block()
    return siblingPayload()
}

fun reviewedLet(): ScopeEnvelope =
    /*<destination>*/"receiver".let { ScopeEnvelope(scopePayload(), siblingPayload()) }/*</destination>*/

fun reviewedAlso(): ScopePayload =
    /*<destination>*/siblingPayload().also { scopePayload() }/*</destination>*/

fun constantReturn(): ScopePayload =
    /*<destination>*/discardScope { scopePayload() }/*</destination>*/

fun safeLet(receiver: String?): ScopeEnvelope? =
    /*<destination>*/receiver?.let { ScopeEnvelope(scopePayload(), siblingPayload()) }/*</destination>*/

fun abruptLet(): ScopePayload {
    "receiver".let {
        scopePayload()
        return /*<destination>*/siblingPayload()/*</destination>*/
    }
}

fun throwingLet(): ScopePayload =
    /*<destination>*/"receiver".let {
        scopePayload()
        throw IllegalStateException("stop")
    }/*</destination>*/

fun nullLet(): ScopeEnvelope? =
    /*<destination>*/(null as String?)?.let { ScopeEnvelope(scopePayload(), siblingPayload()) }/*</destination>*/

data class MutableScopeEnvelope(var selected: ScopePayload)
fun mutatedAlso(): MutableScopeEnvelope =
    /*<destination>*/MutableScopeEnvelope(scopePayload()).also { it.selected = siblingPayload() }/*</destination>*/

fun receiverThrow(): ScopePayload =
    /*<destination>*/siblingPayload().also { throw IllegalStateException("stop") }/*</destination>*/

fun receiverNonLocal(): ScopePayload =
    /*<destination>*/siblingPayload().also { return scopePayload() }/*</destination>*/

fun receiverNothing(): ScopePayload =
    /*<destination>*/siblingPayload().also { error("stop") }/*</destination>*/

fun labeledLet(): ScopeEnvelope =
    /*<destination>*/"receiver".let { return@let ScopeEnvelope(scopePayload(), siblingPayload()) }/*</destination>*/

fun receiverEarlyThrow(): ScopePayload =
    /*<destination>*/siblingPayload().also { throw IllegalStateException("stop"); scopePayload() }/*</destination>*/

data class ScopeItem(val payload: ScopePayload)

fun mapScopeItems(): List<String> = listOf("one")

fun mapSelectedRoute(): List<ScopeItem> =
    /*<destination>*/mapScopeItems()
        .map { ScopeEnvelope(scopePayload(), siblingPayload()) }
        .map { ScopeItem(/*<body-read>*/it/*</body-read>*/.selected) }/*</destination>*/

fun mapSiblingRoute(): List<ScopeItem> =
    /*<destination>*/mapScopeItems()
        .map { ScopeEnvelope(scopePayload(), siblingPayload()) }
        .map { ScopeItem(it.sibling) }/*</destination>*/

fun mapConstantRoute(): List<ScopeItem> =
    /*<destination>*/mapScopeItems()
        .map { ScopeEnvelope(scopePayload(), siblingPayload()) }
        .map { ScopeItem(siblingPayload()) }/*</destination>*/
