package io.github.amichne.kast.api.contract.result

data class ImplementationRelationsResult(
    val records: List<ImplementationRelation>,
    val page: RelationTraversalPageInfo,
)
