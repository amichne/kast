package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryCheckpointStorageBytes
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryRetainedResult

/** An execution refines its initial storage proof once, only when checkpoint accounting needs it. */
internal sealed interface PipelineSeed {
    data object Unmeasured : PipelineSeed

    class Accounted private constructor(val plan: AdmittedQueryPlan, private val roots: RootProof) : PipelineSeed {
        fun retainedRootBytes(graph: QueryImpactRetainedGraph): Long =
            when (val proof = roots) {
                is RootProof.Fixed -> saturatedAdd(SEED_STRUCTURE_BYTES, proof.bytes.value)
                is RootProof.Shared ->
                    pipelineTaskBytes(
                        proof.tasks,
                        graph,
                        saturatedAdd(SEED_STRUCTURE_BYTES, proof.tasks.size.toLong() * ROOT_SLOT_BYTES),
                    )
                RootProof.Impact -> SEED_STRUCTURE_BYTES
            }

        companion object {
            fun create(plan: AdmittedQueryPlan): Accounted =
                Accounted(
                    plan,
                    when (plan) {
                        is AdmittedQueryPlan.Impact -> RootProof.Impact
                        is AdmittedQueryPlan.Symbols,
                        is AdmittedQueryPlan.Text,
                        is AdmittedQueryPlan.Location,
                        is AdmittedQueryPlan.ExactReferences -> fixed(plan)
                        is AdmittedQueryPlan.Retained ->
                            when (plan.source) {
                                is QueryRetainedResult.Symbols,
                                is QueryRetainedResult.Bindings,
                                is QueryRetainedResult.Occurrences -> fixed(plan)
                                is QueryRetainedResult.ValuePaths ->
                                    RootProof.Shared(java.util.List.copyOf(initialTasks(plan)))
                            }
                    },
                )

            private fun fixed(plan: AdmittedQueryPlan): RootProof.Fixed =
                RootProof.Fixed(pipelineTaskBytes(initialTasks(plan), QueryImpactRetainedGraph()).storageBytes())
        }

        private sealed interface RootProof {
            data class Fixed(val bytes: QueryCheckpointStorageBytes) : RootProof

            data class Shared(val tasks: List<PipelineTask>) : RootProof

            data object Impact : RootProof
        }
    }
}

internal fun PipelineSeed.accounted(plan: AdmittedQueryPlan): PipelineSeed.Accounted =
    when (this) {
        PipelineSeed.Unmeasured -> PipelineSeed.Accounted.create(plan)
        is PipelineSeed.Accounted -> this
    }

// Includes the accounted seed, its proof variant and collection metadata. Shared roots also charge each slot.
private const val SEED_STRUCTURE_BYTES = 256L
private const val ROOT_SLOT_BYTES = 8L
