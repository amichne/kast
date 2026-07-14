package io.github.amichne.kast.api.contract.result

data class HierarchyRelationsResult(
    val records: List<TypeHierarchyRelation>,
    val page: RelationTraversalPageInfo,
)
