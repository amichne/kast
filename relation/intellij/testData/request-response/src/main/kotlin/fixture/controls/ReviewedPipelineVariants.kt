package fixture.controls

data class PipelineFeature(val id: String, val payload: ScopePayload)
data class PipelineEntry(val feature: PipelineFeature)
data class PipelineSchema(val entriesById: Map<String, PipelineEntry>)

fun pipelineFeatures(): List<PipelineFeature> =
    mapScopeItems().map { PipelineFeature(it, scopePayload()) }

fun selectorCannotBecomeElement(): List<PipelineFeature> {
    val selector = scopePayload()
    return /*<destination>*/pipelineFeatures().sortedBy { selector.text }/*</destination>*/
}

fun pairKeyCannotBecomeValue(): ScopePayload {
    val pair = scopePayload() to siblingPayload()
    return /*<destination>*/pair.second/*</destination>*/
}

fun pairValueCannotBecomeKey(): ScopePayload {
    val pair = siblingPayload() to scopePayload()
    return /*<destination>*/pair.first/*</destination>*/
}

fun constructorKeyCannotBecomeValue(): ScopePayload {
    val pair = Pair(scopePayload(), siblingPayload())
    return /*<destination>*/pair.second/*</destination>*/
}

fun schemaPipelineElement(): PipelineSchema {
    val features = /*<map-output>*/mapScopeItems()
        .map { PipelineFeature(it, scopePayload()) }/*</map-output>*/
    val sorted = /*<sorted-output>*/features.sortedBy { it.id }/*</sorted-output>*/
    return /*<schema>*/PipelineSchema(/*<destination>*/sorted.associate {
        /*<pair>*/it.id to PipelineEntry(/*<feature-read>*/it/*</feature-read>*/)/*</pair>*/
    }/*</destination>*/)/*</schema>*/
}

fun schemaPipelineWhole(): PipelineSchema {
    val sorted = /*<sorted-output>*/pipelineFeatures().sortedBy { it.id }/*</sorted-output>*/
    return /*<schema>*/PipelineSchema(/*<destination>*/sorted.associate {
        /*<pair>*/it.id to PipelineEntry(/*<feature-read>*/it/*</feature-read>*/)/*</pair>*/
    }/*</destination>*/)/*</schema>*/
}
