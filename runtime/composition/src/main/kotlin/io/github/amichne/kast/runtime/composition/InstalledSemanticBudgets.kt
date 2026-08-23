package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit

/** One finite resource authority shared by installed planning and resulting-state proof. */
internal data class InstalledSemanticBudgets(
    val discovery: SymbolDiscoveryBudget,
    val relation: RelationBudget,
    val traversal: TraversalBudget,
)

/**
 * Proof transition: fixed installed limits to `InstalledSemanticBudgets?`.
 *
 * A non-null result establishes strictly positive discovery, relation, and one-hop traversal
 * limits with no child authority exceeding its aggregate. Null is the closed impossible guard if
 * a fixed literal ceases to satisfy its refined contract. Raw limits are extracted only here.
 */
internal fun installedSemanticBudgets(): InstalledSemanticBudgets? {
    val records = ResultLimit.parse(256).refinedOrNull() ?: return null
    val work = WorkUnitLimit.parse(100_000L).refinedOrNull() ?: return null
    val elapsed = ElapsedTimeLimitMillis.parse(30_000L).refinedOrNull() ?: return null
    val resources = ResourceBudget(records, work, elapsed)
    val discoveryBytes = SymbolDiscoveryByteLimit.parse(4_194_304L).refinedOrNull() ?: return null
    val relationBytes = RelationByteLimit.parse(4_194_304L).refinedOrNull() ?: return null
    val relation = RelationBudget(resources, relationBytes)
    val oneHopElapsed = ElapsedTimeLimitMillis.parse(1_000L).refinedOrNull() ?: return null
    val oneHopRelation = RelationBudget(ResourceBudget(records, work, oneHopElapsed), relationBytes)
    val traversalBytes = TraversalByteLimit.parse(4_194_304L).refinedOrNull() ?: return null
    val depth = TraversalDepthLimit.parse(1).refinedOrNull() ?: return null
    val frontier = TraversalFrontierLimit.parse(256).refinedOrNull() ?: return null
    return InstalledSemanticBudgets(
        SymbolDiscoveryBudget(resources, discoveryBytes),
        relation,
        TraversalBudget(records, traversalBytes, work, elapsed, depth, frontier, oneHopRelation),
    )
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
