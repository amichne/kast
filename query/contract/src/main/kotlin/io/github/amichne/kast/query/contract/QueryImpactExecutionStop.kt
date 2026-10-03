package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.ValueSite
import java.util.Collections

enum class QueryImpactExecutionStopFailure {
    DISCONNECTED_ROUTE,
    NO_REPEATED_SITE,
    CAPACITY_NOT_EXCEEDED,
}

/** Query cutoffs retain positive route or capacity evidence; they never become compiler absence. */
sealed interface QueryImpactExecutionStop {
    val source: ValueSite

    class Cycle
    private constructor(
        val producer: ValueSite,
        val prefix: List<QueryImpactStep>,
        val repeatedAt: Int,
        override val source: ValueSite,
    ) : QueryImpactExecutionStop {
        companion object {
            fun admit(
                producer: ValueSite,
                steps: List<QueryImpactStep>,
            ): Refinement<Cycle, QueryImpactExecutionStopFailure> {
                val visited = mutableListOf(producer)
                var current = producer
                for (step in steps) {
                    if (step.source.identity != current.identity)
                        return Refinement.Rejected(QueryImpactExecutionStopFailure.DISCONNECTED_ROUTE)
                    current = step.target
                    visited += current
                }
                val repeated = visited.dropLast(1).indexOfFirst { it.identity == current.identity }
                return if (repeated < 0) Refinement.Rejected(QueryImpactExecutionStopFailure.NO_REPEATED_SITE)
                else
                    Refinement.Refined(Cycle(producer, Collections.unmodifiableList(steps.toList()), repeated, current))
            }
        }
    }

    class CheckpointCapacity
    private constructor(
        override val source: ValueSite,
        val required: RelationByteCount,
        val available: RelationByteLimit,
    ) : QueryImpactExecutionStop {
        companion object {
            fun admit(
                source: ValueSite,
                required: RelationByteCount,
                available: RelationByteLimit,
            ): Refinement<CheckpointCapacity, QueryImpactExecutionStopFailure> =
                if (required.value <= available.value)
                    Refinement.Rejected(QueryImpactExecutionStopFailure.CAPACITY_NOT_EXCEEDED)
                else Refinement.Refined(CheckpointCapacity(source, required, available))
        }
    }
}
