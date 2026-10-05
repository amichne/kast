package io.github.amichne.kast.protocol.contract

/** Supplying a nested body preserves its source and mapping without proving activation. */
sealed interface QueryCallbackBodySupplyDocument {
    data class Invocation(val occurrence: RelationOccurrenceDocument) : QueryCallbackBodySupplyDocument

    data class Returned(val occurrence: RelationOccurrenceDocument) : QueryCallbackBodySupplyDocument

    data object Unsupported : QueryCallbackBodySupplyDocument

    data object Stored : QueryCallbackBodySupplyDocument
}

data class QueryCallbackBodyBindingDocument(
    val body: QueryCallbackBodyDocument.Anonymous,
    val supply: QueryCallbackBodySupplyDocument,
    val binding: QueryCallbackBindingDocument,
    val obligations: BoundedProtocolList<QueryCallbackFlowCauseDocument>,
)
