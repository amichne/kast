package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalExtent

/** Pure attenuation preserves semantic mode and the finite page target. */
internal fun queryTraversalBudget(
    resources: ResourceBudget,
    records: ResultLimit,
    returnedBytes: TraversalByteLimit,
    requestedExtent: TraversalExtent,
    ceiling: TraversalBudget,
): TraversalBudget? {
    val oneHopBytes = RelationByteLimit.parse(minOf(returnedBytes.value, ceiling.oneHop.returnedBytes.value)).refined()
    return when (resources) {
        ResourceBudget.RunnerControlled ->
            TraversalBudget.RunnerControlled(
                records,
                returnedBytes,
                requestedExtent,
                io.github.amichne.kast.relation.contract.RunnerControlledRelationBudget.create(
                    oneHopBytes,
                    records.intersect(ceiling.oneHop.outputResults),
                ),
            )
        is ResourceBudget.Bounded ->
            when (ceiling) {
                is TraversalBudget.RunnerControlled -> null
                is TraversalBudget.Bounded ->
                    when (val hop = ceiling.oneHop.resources) {
                        ResourceBudget.RunnerControlled -> null
                        is ResourceBudget.Bounded -> {
                            val work =
                                WorkUnitLimit.parse(minOf(resources.workUnitLimit.value, ceiling.workUnits.value))
                                    .refined()
                            val time =
                                ElapsedTimeLimitMillis.parse(
                                        minOf(resources.elapsedTimeLimit.value, ceiling.elapsedTime.value)
                                    )
                                    .refined()
                            TraversalBudget(
                                records,
                                returnedBytes,
                                work,
                                time,
                                requestedExtent,
                                ceiling.frontier,
                                RelationBudget(
                                    ResourceBudget(
                                        records.intersect(ceiling.oneHop.outputResults),
                                        WorkUnitLimit.parse(minOf(work.value, hop.workUnitLimit.value)).refined(),
                                        ElapsedTimeLimitMillis.parse(minOf(time.value, hop.elapsedTimeLimit.value))
                                            .refined(),
                                    ),
                                    oneHopBytes,
                                ),
                            )
                        }
                    }
            }
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Derived traversal authority violated its invariant: $failure")
    }
