package io.github.amichne.kast.api.contract.result

sealed interface HierarchyRelationsResult {
    data class Available(
        val records: List<TypeHierarchyRelation>,
        val page: RelationTraversalPageInfo,
    ) : HierarchyRelationsResult

    data class Limited(
        val evidence: RelationshipResultEvidence.Limited,
    ) : HierarchyRelationsResult
}
