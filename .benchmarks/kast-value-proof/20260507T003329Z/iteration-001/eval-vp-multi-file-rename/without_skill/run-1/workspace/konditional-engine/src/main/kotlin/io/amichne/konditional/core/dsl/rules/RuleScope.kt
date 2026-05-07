@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.core.dsl.rules

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.context.Context
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.dsl.FlagScope
import io.amichne.konditional.core.dsl.KonditionalDsl
import io.amichne.konditional.core.features.Feature
import io.amichne.konditional.core.dsl.rules.targeting.scopes.AnyOfScope
import io.amichne.konditional.core.dsl.rules.targeting.scopes.LocaleTargetingScope
import io.amichne.konditional.core.dsl.rules.targeting.scopes.PlatformTargetingScope
import io.amichne.konditional.core.dsl.rules.targeting.scopes.StableIdTargetingScope
import io.amichne.konditional.core.dsl.rules.targeting.scopes.VersionTargetingScope

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
interface RuleScope<C : Context> : ContextRuleScope<C>,
                                   LocaleTargetingScope<C>,
                                   PlatformTargetingScope<C>,
                                   VersionTargetingScope<C>,
                                   StableIdTargetingScope<C> {

    /**
     * Defines an OR-disjunction of targeting constraints within this rule.
     *
     * The group matches when *any* contained constraint matches. The whole OR group
     * is composed with AND semantics relative to other targeting in this rule
     * (i.e., a leaf in the enclosing [io.amichne.konditional.rules.targeting.Targeting.All]).
     *
     * Empty blocks are silently ignored — no leaf is appended.
     *
     * Example:
     * ```kotlin
     * rule {
     *     anyOf {
     *         locales("en", "fr")
     *         platforms(Platform.IOS)
     *     }
     * } yields value
     * ```
     *
     * @param build DSL block configuring the OR group's targeting branches
     */
    fun anyOf(build: AnyOfScope<C>.() -> Unit)

    companion object {
        private fun captureRuleCallSite(): String? =
            Throwable("Rule call site capture")
                .stackTrace
                .asSequence()
                .dropWhile { it.className.startsWith("io.amichne.konditional.core.dsl.") }
                .dropWhile { it.className.startsWith("io.amichne.konditional.internal.builders.") }
                .firstOrNull()
                ?.let { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
    }

    /**
     * DSL wrapper representing a partially-specified rule: criteria first, value second.
     *
     * Created via [io.amichne.konditional.core.dsl.FlagScope.rule] and completed via [yields].
     */
    @KonditionalDsl
    class Prefix<T : Any, C : Context, out M : Namespace> internal constructor(
        private val scope: FlagScope<T, C, @UnsafeVariance M>,
        private val criteriaBuild: RuleScope<C>.() -> Unit,
    ) {
        private val host: YieldingScopeHost? = scope as? YieldingScopeHost
        @Suppress("UNCHECKED_CAST")
        private val contextualHost: ContextualYieldingScope<T, C>? = scope as? ContextualYieldingScope<T, C>
        private val pendingToken: PendingYieldToken =
            PendingYieldToken(callSite = captureRuleCallSite()).also { host?.registerPendingYield(it) }

        /**
         * Completes the rule declaration by assigning the value to yield when the criteria matches.
         *
         * Semantics:
         * `rule { criteria } yields VALUE` ≡ `rule(VALUE) { criteria }`
         * `rule { criteria } yields { resolver() }` resolves lazily when the rule matches
         *
         * When invoked from the criteria-first DSL, this also closes the pending rule
         * and makes it eligible for validation during flag construction.
         */
        infix fun yields(value: T): Postfix = host
            ?.commitYield(pendingToken) { scope.rule(value, criteriaBuild) }
            ?.let { Postfix }
            ?: run {
                scope.rule(value, criteriaBuild)
                Postfix
            }

        /**
         * Completes the rule declaration using a deferred resolver.
         *
         * The resolver executes only when the rule matches, with access to the
         * current evaluation context and compositional feature reads via
         * [RuleValueScope.evaluate].
         */
        infix fun yields(valueResolver: RuleValueResolver<C, T>): Postfix = host
            ?.commitYield(pendingToken) { commitDeferredRule(valueResolver) }
            ?.let { Postfix }
            ?: run {
                commitDeferredRule(valueResolver)
                Postfix
            }

        /**
         * Completes the rule declaration by yielding the value of another [Feature].
         *
         * The feature is evaluated lazily when the rule matches, with the same
         * contextual/registry semantics as deferred [yields] resolvers.
         */
        infix fun <M2 : Namespace> yields(feature: Feature<T, C, M2>): Postfix =
            yields { feature() }

        private fun commitDeferredRule(valueResolver: RuleValueResolver<C, T>) {
            val resolverHost = contextualHost
                ?: error("Deferred yields are not supported by this FlagScope implementation.")
            resolverHost.ruleResolved(
                valueResolver = valueResolver,
                build = criteriaBuild,
            )
        }
    }

    /**
     * DSL wrapper representing a partially-specified rule: criteria first, value second,
     * using a composable scope.
     *
     * Created via [FlagScope.ruleScoped] and completed via [yields].
     */
    @KonditionalDsl
    @KonditionalInternalApi
    class ScopedPrefix<T : Any, C : Context, out M : Namespace> internal constructor(
        private val scope: FlagScope<T, C, @UnsafeVariance M>,
        private val criteriaBuild: ContextRuleScope<C>.() -> Unit,
    ) {
        private val host: YieldingScopeHost? = scope as? YieldingScopeHost
        @Suppress("UNCHECKED_CAST")
        private val contextualHost: ContextualYieldingScope<T, C>? = scope as? ContextualYieldingScope<T, C>
        private val pendingToken: PendingYieldToken =
            PendingYieldToken(callSite = captureRuleCallSite()).also { host?.registerPendingYield(it) }

        /**
         * Completes the rule declaration by assigning the value to yield when the criteria matches.
         *
         * Semantics:
         * `ruleScoped { criteria } yields VALUE` ≡ `ruleScoped(VALUE) { criteria }`
         * `ruleScoped { criteria } yields { resolver() }` resolves lazily when the rule matches
         *
         * When invoked from the criteria-first DSL, this also closes the pending rule
         * and makes it eligible for validation during flag construction.
         */
        infix fun yields(value: T): Postfix = host
            ?.commitYield(pendingToken) { scope.ruleScoped(value, criteriaBuild) }
            ?.let { Postfix }
            ?: run {
                scope.ruleScoped(value, criteriaBuild)
                Postfix
            }

        /**
         * Completes the rule declaration using a deferred resolver in scoped mode.
         */
        infix fun yields(valueResolver: RuleValueResolver<C, T>): Postfix = host
            ?.commitYield(pendingToken) { commitDeferredRule(valueResolver) }
            ?.let { Postfix }
            ?: run {
                commitDeferredRule(valueResolver)
                Postfix
            }

        /**
         * Completes the scoped rule declaration by yielding the value of another [Feature].
         *
         * The feature is evaluated lazily when the rule matches, with the same
         * contextual/registry semantics as deferred [yields] resolvers.
         */
        infix fun <M2 : Namespace> yields(feature: Feature<T, C, M2>): Postfix =
            yields { feature() }

        private fun commitDeferredRule(valueResolver: RuleValueResolver<C, T>) {
            val resolverHost = contextualHost
                ?: error("Deferred yields are not supported by this FlagScope implementation.")
            resolverHost.ruleScopedResolved(
                valueResolver = valueResolver,
                build = criteriaBuild,
            )
        }
    }

    /**
     * Marker value returned after completing a criteria-first rule.
     */
    @KonditionalDsl
    object Postfix
}
