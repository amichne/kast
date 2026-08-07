package io.github.amichne.kast.indexer.gradle.settlement

import io.github.amichne.kast.api.contract.PositiveInt

/**
 * Construction transition:
 * `(RuntimeProgressWaitPolicy, PositiveInt, PositiveInt) -> GradleModelSettlementPolicy`.
 *
 * Retains the already-proven wait policy, stability threshold, and trace bound
 * as one settlement capability. Raw policy primitives are admitted only while
 * constructing the three input proof types.
 */
@ConsistentCopyVisibility
data class GradleModelSettlementPolicy private constructor(
    val progressWaitPolicy: RuntimeProgressWaitPolicy,
    val requiredStableObservations: PositiveInt,
    val maxTransitionTraceEntries: PositiveInt,
) {
    companion object {
        fun derive(
            progressWaitPolicy: RuntimeProgressWaitPolicy,
            requiredStableObservations: PositiveInt,
            maxTransitionTraceEntries: PositiveInt,
        ): GradleModelSettlementPolicy = GradleModelSettlementPolicy(
            progressWaitPolicy,
            requiredStableObservations,
            maxTransitionTraceEntries,
        )

        @JvmStatic
        fun standard(): GradleModelSettlementPolicy =
            derive(
                progressWaitPolicy = RuntimeProgressWaitPolicy.standard(),
                requiredStableObservations = PositiveInt(10),
                maxTransitionTraceEntries = PositiveInt(64),
            )
    }
}
