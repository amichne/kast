package io.amichne.konditional.core.dsl.rules

import io.amichne.konditional.context.Context
import io.amichne.konditional.core.dsl.KonditionalDsl

/**
 * DSL scope for rule configuration.
 *
 * This interface defines the public API for configuring targeting rules.
 * Users cannot instantiate implementations of this interface directly - it is only
 * available as a receiver in DSL blocks through internal implementations.
 *
 * Example usage:
 * ```kotlin
 * rule {
 *     locales(AppLocale.UNITED_STATES, AppLocale.CANADA)
 *     platforms(Platform.IOS, Platform.ANDROID)
 *     versions {
 *         min(1, 2, 0)
 *         max(2, 0, 0)
 *     }
 *     rampUp {  RampUp.create(50.0) }
 *     note("RampUp to mobile users only")
 * }
 * ```
 *
 * @param C The contextFn type the rule evaluates against
 * @since 0.0.2
 */
@KonditionalDsl
interface RuleScopeBase<C : Context> {
    /**
     * Explicitly marks this rule as matching all contexts.
     *
     * This is a no-op: an empty rule already matches all contexts. Use this to make "catch-all"
     * intent explicit in reviews and when reading configuration.
     */
    fun always() {}

    /**
     * Alias for [always].
     */
    fun matchAll() = always()
}
