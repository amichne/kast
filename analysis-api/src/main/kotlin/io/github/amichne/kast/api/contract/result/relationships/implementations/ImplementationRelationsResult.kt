package io.github.amichne.kast.api.contract.result

sealed interface ImplementationRelationsResult {
    data class Available(
        val records: List<ImplementationRelation>,
        val page: RelationTraversalPageInfo,
    ) : ImplementationRelationsResult

    data class Limited(
        val evidence: RelationshipResultEvidence.Limited,
    ) : ImplementationRelationsResult
}
