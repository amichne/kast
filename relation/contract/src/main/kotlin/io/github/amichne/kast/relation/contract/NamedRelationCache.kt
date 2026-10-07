package io.github.amichne.kast.relation.contract

sealed interface NamedRelationCacheLookup {
    data object Miss : NamedRelationCacheLookup

    data class Found(val complete: RelationCompilation.Complete) : NamedRelationCacheLookup
}

/** Optional complete adjacency; misses and rejected restoration always continue ordinary native extraction. */
interface NamedRelationCachePort {
    fun find(
        request: RelationRequest,
        readmit: (CompleteNamedRelationPartition) -> NamedRelationReadmission,
    ): NamedRelationCacheLookup

    fun retain(complete: RelationCompilation.Complete)

    fun admitted(complete: RelationCompilation.Complete)

    data object Disabled : NamedRelationCachePort {
        override fun find(
            request: RelationRequest,
            readmit: (CompleteNamedRelationPartition) -> NamedRelationReadmission,
        ) = NamedRelationCacheLookup.Miss

        override fun retain(complete: RelationCompilation.Complete) = Unit

        override fun admitted(complete: RelationCompilation.Complete) = Unit
    }
}
