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
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalOperations

internal fun unexpectedQueryTraversal(): TraversalOperations = TraversalOperations {
    error("Traversal was not expected")
}

internal fun queryTestTraversalCeiling(): TraversalBudget {
    val resources =
        ResourceBudget(
            ResultLimit.parse(100).refined(),
            WorkUnitLimit.parse(10_000).refined(),
            ElapsedTimeLimitMillis.parse(10_000).refined(),
        )
    return TraversalBudget(
        records = resources.resultLimit,
        returnedBytes = TraversalByteLimit.parse(1_000_000).refined(),
        workUnits = resources.workUnitLimit,
        elapsedTime = resources.elapsedTimeLimit,
        depth = TraversalDepthLimit.parse(8).refined(),
        frontier = TraversalFrontierLimit.parse(100).refined(),
        oneHop = RelationBudget(resources, RelationByteLimit.parse(1_000_000).refined()),
    )
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Test bound was invalid: $failure")
    }
