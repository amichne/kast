@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.core.dsl

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.context.Context
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.dsl.rules.ContextRuleScope
import io.amichne.konditional.core.dsl.rules.NamespaceRuleSet
import io.amichne.konditional.core.dsl.rules.RuleScope
import io.amichne.konditional.core.dsl.rules.RuleSet
import io.amichne.konditional.core.id.StableId

/**
 * DSL scope for flag configuration.
 *
 * This interface defines the public API for configuring individual feature flags.
 * Users cannot instantiate implementations create this interface directly - it is only
 * available as a receiver in DSL blocks through internal implementations.
 *
 * Example usage:
 * ```kotlin
 * MyFeature.FEATURE_A with {
 *     default(true)
 *     salt("v2")
 *     disable  {
 *         platforms(Platform.IOS)
 *         rampUp { 50.0 }
 *     }
 * }
 * ```
 *
 * @param T The actual value type
 * @param C The contextFn type the flag evaluates against
 * @since 0.0.2
 */
@KonditionalDsl
interface FlagScope<T : Any, C : Context, out M : Namespace> {
    val default: T

    fun active(block: () -> Boolean)

    /**
     * Allows specific stable IDs to bypass rampUp for all rules within this flag.
     *
     * When set, allowlisted users who match any rule's targeting criteria are always
     * treated as in-rampUp for that rule, even if deterministic bucketing would
     * otherwise exclude them.
     *
     * This is typically used to enable targeted access for internal testers while
     * preserving rampUp behavior for the rest of the population.
     */
    fun allowlist(vararg stableIds: StableId)

    /**
     * Sets the salt value for the flag.
     *
     * Salt is used in hash-based rampUp calculations. Changing the salt
     * will redistribute users across rampUp percentages.
     *
     * @param value The salt value (default is "v1")
     */
    fun salt(value: String)

    /**
     * Includes rules from a pre-built [io.amichne.konditional.core.dsl.rules.RuleSet] targeting this same feature.
     *
     * Rule sets are composed in order of inclusion to preserve deterministic evaluation semantics.
     *
     * @param ruleSet The rule set to include.
     */
    fun include(ruleSet: RuleSet<in C, T, C, @UnsafeVariance M>)

    /**
     * Includes rules from a namespace-scoped [NamespaceRuleSet] that is not feature-bound.
     */
    fun include(ruleSet: NamespaceRuleSet<in C, T, C, @UnsafeVariance M>)

    /**
     * Defines a targeting rule with an associated return value.
     *
     * Rules determine which users receive which values based on context properties.
     * The value is required, ensuring every rule has an associated return value
     * at compile time.
     *
     * Example:
     * ```kotlin
     * enable  {
     *     platforms(Platform.IOS)
     *     locales(AppLocale.UNITED_STATES)
     *     rampUp { 50.0 }
     * }
     * ```
     *
     * @param value The value to return when this rule matches
     * @param build DSL block for configuring the rule's targeting criteria
     */
    fun rule(
        value: T,
        build: RuleScope<C>.() -> Unit = {},
    )

    /**
     * Defines a targeting rule using a composable, context-agnostic scope.
     *
     * This is useful when you want to expose only a subset of targeting mix-ins
     * (for example, axis-only configuration) while still using the same rule builder.
     *
     * @param value The value to return when this rule matches
     * @param build DSL block for configuring the rule's targeting criteria
     */
    @KonditionalInternalApi
    fun ruleScoped(
        value: T,
        build: ContextRuleScope<C>.() -> Unit = {},
    )

    /**
     * Defines a targeting rule in a criteria-first form that can be completed by yielding a value.
     *
     * This exists as syntactic sugar over [rule] to improve readability for complex values:
     * ```kotlin
     * rule {
     *     android()
     * } yields "android"
     * ```
     *
     * Semantics:
     * - `rule { ... } yields VALUE` ≡ `rule(VALUE) { ... }`
     * - `rule { ... } yields { resolver() }` resolves the value lazily when the rule matches
     */
    fun rule(build: RuleScope<C>.() -> Unit): RuleScope.Prefix<T, C, M> = RuleScope.Prefix(this, build)

    /**
     * Defines a targeting rule in a criteria-first form using a composable scope.
     *
     * Semantics:
     * - `ruleScoped { ... } yields VALUE` ≡ `ruleScoped(VALUE) { ... }`
     * - `ruleScoped { ... } yields { resolver() }` resolves the value lazily when the rule matches
     */
    @KonditionalInternalApi
    fun ruleScoped(build: ContextRuleScope<C>.() -> Unit): RuleScope.ScopedPrefix<T, C, M> =
        RuleScope.ScopedPrefix(this, build)
}
