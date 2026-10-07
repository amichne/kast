package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CompleteNamedRelationPartition
import io.github.amichne.kast.relation.contract.NamedRelationCacheLookup
import io.github.amichne.kast.relation.contract.NamedRelationCachePort
import io.github.amichne.kast.relation.contract.NamedRelationReadmission
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.topology.build.SemanticCallbackFactStore
import io.github.amichne.kast.topology.build.SemanticCallbackPublication
import io.github.amichne.kast.topology.build.SemanticNamedRelationLookup
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

internal class HostedNamedRelationFacts(
    private val store: SemanticCallbackFactStore,
    private val observation: IntellijReadObservation,
    private val snapshot: (RelationRequest) -> HostedCallbackPartition,
) : NamedRelationCachePort {
    override fun find(
        request: RelationRequest,
        readmit: (CompleteNamedRelationPartition) -> NamedRelationReadmission,
    ): NamedRelationCacheLookup {
        val current =
            when (val admitted = snapshot(request)) {
                is HostedCallbackPartition.Available -> admitted.snapshot
                is HostedCallbackPartition.Rejected -> return NamedRelationCacheLookup.Miss
            }
        return when (val found = store.findNamed(current, request)) {
            SemanticNamedRelationLookup.Missing -> NamedRelationCacheLookup.Miss
            is SemanticNamedRelationLookup.Rejected -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_REJECTED)
                NamedRelationCacheLookup.Miss
            }
            is SemanticNamedRelationLookup.Invalidated -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_INVALIDATED)
                observation.count(IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_INVALIDATED)
                observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
                NamedRelationCacheLookup.Miss
            }
            is SemanticNamedRelationLookup.Reusable -> restore(current, request, readmit(found.previous))
        }
    }

    private fun restore(
        current: SemanticDependencySnapshot,
        request: RelationRequest,
        restored: NamedRelationReadmission,
    ): NamedRelationCacheLookup {
        val complete =
            when (restored) {
                is Refinement.Refined -> restored.value
                is Refinement.Rejected -> return restoreRejected()
            }
        if (complete.batch.request !== request) return restoreRejected()
        val partition =
            when (val admitted = CompleteNamedRelationPartition.fromCompiler(complete)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return restoreRejected()
            }
        return when (publish(current, partition)) {
            SemanticCallbackPublication.Published,
            SemanticCallbackPublication.CapacityExceeded -> NamedRelationCacheLookup.Found(complete)
            is SemanticCallbackPublication.Rejected -> NamedRelationCacheLookup.Miss
        }
    }

    private fun restoreRejected(): NamedRelationCacheLookup {
        observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
        return NamedRelationCacheLookup.Miss
    }

    override fun retain(complete: RelationCompilation.Complete) {
        val partition =
            when (val admitted = CompleteNamedRelationPartition.fromCompiler(complete)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> {
                    observation.count(IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_INELIGIBLE)
                    return
                }
            }
        val current =
            when (val admitted = snapshot(complete.batch.request)) {
                is HostedCallbackPartition.Available -> admitted.snapshot
                is HostedCallbackPartition.Rejected -> return
            }
        observation.count(IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_EXTRACTED)
        observation.count(IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_EXTRACTED)
        publish(current, partition)
    }

    override fun admitted(complete: RelationCompilation.Complete) {
        observation.count(IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_REUSED)
        observation.count(IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_REUSED)
    }

    private fun publish(
        snapshot: SemanticDependencySnapshot,
        partition: CompleteNamedRelationPartition,
    ): SemanticCallbackPublication {
        val result = store.publishNamed(snapshot, partition)
        observation.count(
            when (result) {
                SemanticCallbackPublication.Published -> IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_PUBLISHED
                SemanticCallbackPublication.CapacityExceeded,
                is SemanticCallbackPublication.Rejected -> IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_REJECTED
            }
        )
        return result
    }
}
