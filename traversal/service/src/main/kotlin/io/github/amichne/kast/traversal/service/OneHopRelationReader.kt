package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationContinuation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.traversal.contract.TraversalNode

internal sealed interface OneHopRelationPosition {
    data object Start : OneHopRelationPosition
    data class Resume(val continuation: RelationContinuation) : OneHopRelationPosition
}

internal data class OneHopRelationRequest(
    val node: TraversalNode,
    val meaning: RelationMeaning,
    val scope: SymbolSearchScope,
    val budget: RelationBudget,
    val position: OneHopRelationPosition,
)

internal enum class OneHopElapsedFailure {
    NEGATIVE,
}

@JvmInline
internal value class OneHopElapsedMillis private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<OneHopElapsedMillis, OneHopElapsedFailure>`.
         *
         * Establishes a non-negative elapsed-time observation for one bounded reader call.
         * [OneHopElapsedFailure] is the closed expected failure. Raw time extraction is permitted
         * only inside a one-hop reader implementation or deterministic test fixture.
         */
        fun parse(raw: Long): Refinement<OneHopElapsedMillis, OneHopElapsedFailure> =
            if (raw >= 0L) Refinement.Refined(OneHopElapsedMillis(raw))
            else Refinement.Rejected(OneHopElapsedFailure.NEGATIVE)
    }
}

internal data class OneHopRelationRead(
    val result: RelationReadResult,
    val elapsedMillis: OneHopElapsedMillis,
)

/** Module-private pure-effect port for one already-bounded detached relation page. */
internal fun interface OneHopRelationReader {
    /**
     * Proof transition: `OneHopRelationRequest -> OneHopRelationRead`.
     *
     * A returned page must preserve the requested exact node, meaning, scope, generation, budget,
     * and relation continuation. Expected semantic rejection remains finite inside
     * [RelationReadResult.Rejected]. Live platform state cannot cross this boundary.
     */
    suspend fun read(request: OneHopRelationRequest): OneHopRelationRead
}
