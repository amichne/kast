package io.github.amichne.kast.api.contract.result

sealed interface CallRelationsResult {
    data class Available(
        val records: List<CallRelation>,
        val page: RelationTraversalPageInfo,
    ) : CallRelationsResult

    data class Limited(
        val evidence: RelationshipResultEvidence.Limited,
        val records: List<CallRelation> = emptyList(),
    ) : CallRelationsResult {
        init {
            require(evidence.cardinality.knownMinimum() >= records.size) {
                "Limited call relationship evidence must cover every returned record"
            }
        }
    }
}
