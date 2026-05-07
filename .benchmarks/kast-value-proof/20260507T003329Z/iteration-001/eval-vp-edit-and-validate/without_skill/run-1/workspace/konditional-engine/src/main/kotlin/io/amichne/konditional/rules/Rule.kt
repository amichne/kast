package io.amichne.konditional.rules

import io.amichne.konditional.context.Context
import io.amichne.konditional.context.RampUp
import io.amichne.konditional.core.id.HexId
import io.amichne.konditional.rules.targeting.Targeting
import io.amichne.konditional.values.RuleId

/**
 * A composable rule that combines a structured [Targeting] tree with rampUp metadata.
 *
 * ## Evaluation contract
 * `matches(ctx)` iff `targeting.matches(ctx)` — all extension and capability-narrowed
 * logic lives in the [targeting] tree as [Targeting.Custom] or [Targeting.Guarded] leaves.
 *
 * ## Specificity
 * `specificity()` is structurally derived from the [targeting] tree; each present
 * leaf contributes 1 (or its declared weight for [Targeting.Custom]).
 *
 * ## Determinism
 * Same [targeting] + same context produces the same result. No ambient state, no randomness.
 *
 * @param C The context type this rule evaluates against.
 * @property rampUp Percentage (0-100) of matching contexts that receive the value.
 * @property rampUpAllowlist Stable IDs that always bypass rampUp.
 * @property note Optional human-readable description for observability.
 * @property targeting Structured AND-conjunction of targeting constraints.
 * @property ruleId Stable UUID identity for this rule across serialization round-trips.
 */
@ConsistentCopyVisibility
data class Rule<in C : Context> internal constructor(
    val rampUp: RampUp = RampUp.default,
    internal val rampUpAllowlist: Set<HexId> = emptySet(),
    val note: String? = null,
    val targeting: Targeting.All<@UnsafeVariance C> = Targeting.catchAll(),
    val ruleId: RuleId = RuleId.unspecified,
) {
    /** Returns true iff all targeting constraints match [context]. */
    fun matches(context: C): Boolean = targeting.matches(context)

    /** Specificity derived structurally from the targeting tree. */
    fun specificity(): Int = targeting.specificity()
}
