package fixture.controls

fun collectionKeys(): List<String> = listOf("left", "right")

fun pairFirstMatches(): ScopePayload {
    val pair = /*<tuple>*/scopePayload() to siblingPayload()/*</tuple>*/
    return /*<destination>*/pair.first/*</destination>*/
}

fun pairSecondMatches(): ScopePayload {
    val pair = /*<tuple>*/siblingPayload() to scopePayload()/*</tuple>*/
    return /*<destination>*/pair.second/*</destination>*/
}

fun constructorFirstMatches(): ScopePayload {
    val pair = /*<tuple>*/Pair(scopePayload(), siblingPayload())/*</tuple>*/
    return /*<destination>*/pair.first/*</destination>*/
}

fun constructorSecondMatches(): ScopePayload {
    val pair = /*<tuple>*/Pair(siblingPayload(), scopePayload())/*</tuple>*/
    return /*<destination>*/pair.second/*</destination>*/
}

fun distinctSortKeys(): List<PipelineFeature> {
    val features = collectionKeys().map { PipelineFeature(it, scopePayload()) }
    return /*<destination>*/features.sortedBy { it.id }/*</destination>*/
}

fun equalSortKeys(): List<PipelineFeature> {
    val features = collectionKeys().map { PipelineFeature(it, scopePayload()) }
    return /*<destination>*/features.sortedBy { 0 }/*</destination>*/
}

fun distinctKeyAssociation(): Map<String, ScopePayload> =
    /*<destination>*/listOf("left", "right").associate { it to scopePayload() }/*</destination>*/

fun duplicateKeyAssociation(): Map<String, ScopePayload> =
    /*<destination>*/listOf("same", "same").associate { it to scopePayload() }/*</destination>*/

fun unknownKeyAssociation(): Map<String, ScopePayload> =
    /*<destination>*/collectionKeys().associate { it to scopePayload() }/*</destination>*/

fun constantAssociationDropsInput(): Map<String, ScopePayload> {
    val features = collectionKeys().map { PipelineFeature(it, scopePayload()) }
    return /*<destination>*/features.associate { "constant" to siblingPayload() }/*</destination>*/
}

fun associationKeyCannotBecomeValue(): Map<ScopePayload, ScopePayload> =
    /*<destination>*/collectionKeys().associate { scopePayload() to siblingPayload() }/*</destination>*/

fun associationValueCannotBecomeKey(): Map<ScopePayload, ScopePayload> =
    /*<destination>*/collectionKeys().associate { siblingPayload() to scopePayload() }/*</destination>*/
