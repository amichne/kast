package io.github.amichne.kast.api.contract.result

sealed interface CallRelationsResult {
    data class Available(
        val records: List<CallRelation>,
        val page: RelationTraversalPageInfo,
    ) : CallRelationsResult

    data class Limited(
        val evidence: RelationshipResultEvidence.Limited,
    ) : CallRelationsResult
}
