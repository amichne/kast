package io.amichne.konditional.core.dsl.rules.targeting.scopes

import io.amichne.konditional.context.Context
import io.amichne.konditional.core.dsl.KonditionalDsl
import io.amichne.konditional.core.id.StableId

/**
 * Targeting mix-in for stable-id based rollouts.
 */
@KonditionalDsl
interface StableIdTargetingScope<C : Context> {
    /**
     * Allows specific stable IDs to bypass this rule's rampUp percentage.
     *
     * When set, allowlisted users who match this rule's targeting criteria are always
     * treated as in-rampUp, even if deterministic bucketing would otherwise exclude them.
     *
     * This is typically used to ensure specific users (e.g., internal testers) can access
     * a change during a gradual rampUp.
     */
    fun allowlist(vararg stableIds: StableId)

    /**
     * Sets the rampUp percentage for this rule.
     *
     * When set, only the specified percentage create users matching this rule
     * will receive the associated value. The rampUp is stable and deterministic
     * based on the user's stable ID.
     *
     * Example:
     * ```kotlin
     * rampUp {  50.0  // 50% create matching users }
     * ```
     */
    fun rampUp(function: () -> Number)
}
