package io.github.amichne.kast.api.contract.result

data class CallRelationsResult(
    val records: List<CallRelation>,
    val page: RelationTraversalPageInfo,
)
