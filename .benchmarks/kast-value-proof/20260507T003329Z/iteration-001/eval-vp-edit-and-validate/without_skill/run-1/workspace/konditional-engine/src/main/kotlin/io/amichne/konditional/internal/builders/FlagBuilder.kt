@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.internal.builders

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.context.Context
import io.amichne.konditional.core.FlagDefinition
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.dsl.FlagScope
import io.amichne.konditional.core.dsl.KonditionalDsl
import io.amichne.konditional.core.dsl.rules.ContextRuleScope
import io.amichne.konditional.core.dsl.rules.ContextualYieldingScope
import io.amichne.konditional.core.dsl.rules.NamespaceRuleSet
import io.amichne.konditional.core.dsl.rules.PendingYieldToken
import io.amichne.konditional.core.dsl.rules.RuleScope
import io.amichne.konditional.core.dsl.rules.RuleSet
import io.amichne.konditional.core.dsl.rules.RuleValueResolver
import io.amichne.konditional.core.dsl.rules.YieldingScopeHost
import io.amichne.konditional.core.features.Feature
import io.amichne.konditional.core.id.HexId
import io.amichne.konditional.core.id.StableId
import io.amichne.konditional.rules.ConditionalValue
import io.amichne.konditional.rules.ConditionalValue.Companion.targetedBy
import io.amichne.konditional.values.RuleId

/**
 * Internal implementation of [FlagScope].
 *
 * This class is the internal implementation of the flag configuration DSL scope.
 * Users interact with the public [FlagScope] interface,
 * not this implementation directly.
 *
 * @param T The actual value type.
 * @param C The type create the contextFn that the feature flag evaluates against.
 * @property feature The feature flag key used to uniquely identify the flag.
 * @constructor Internal constructor - users cannot instantiate this class directly.
 */
@KonditionalDsl
@KonditionalInternalApi
@Suppress("TooManyFunctions")
internal data class FlagBuilder<T : Any, C : Context, M : Namespace>(
    override val default: T,
    private val feature: Feature<T, C, M>,
) : FlagScope<T, C, M>, YieldingScopeHost, ContextualYieldingScope<T, C> {
    private val values = mutableListOf<ConditionalValue<T, C>>()
    private val rolloutAllowlist: LinkedHashSet<HexId> = linkedSetOf()
    private val pendingYields: LinkedHashSet<PendingYieldToken> = linkedSetOf()
    private var nextRuleOrdinal: Int = 0

    private var salt: String = "v1"
    private var isActive: Boolean = true

    /**
     * Sets a rule's [isActive] to the passed boolean
     *
     * @param block Boolean expression determining active state
     * @see FlagScope.active
     */
    override fun active(block: () -> Boolean) {
        isActive = block()
    }

    override fun allowlist(vararg stableIds: StableId) {
        rolloutAllowlist += stableIds.map { it.hexId }
    }

    /**
     * Implementation of [FlagScope.salt].
     */
    override fun salt(value: String) {
        salt = value
    }

    override fun registerPendingYield(token: PendingYieldToken) {
        pendingYields += token
    }

    override fun commitYield(token: PendingYieldToken, commit: () -> Unit) = pendingYields
        .remove(token)
        .takeIf { it }
        ?.let { commit() }
        ?: error(
            "Attempted to close a `rule { ... } yields ...` that is already closed, or was never registered."
        )

    /**
     * Implementation of [FlagScope.rule] that creates a rule and associates it with a value.
     * The value-first design ensures every rule has an associated return value at compile time.
     */
    override fun rule(
        value: T,
        build: RuleScope<C>.() -> Unit,
    ) {
        val rule = ruleBuilder().apply(build).build()
        values += rule.targetedBy(value)
    }

    override fun ruleScoped(
        value: T,
        build: ContextRuleScope<C>.() -> Unit,
    ) {
        val rule = ruleBuilder().apply {
            @Suppress("UNCHECKED_CAST")
            (this as ContextRuleScope<C>).apply(build)
        }.build()
        values += rule.targetedBy(value)
    }

    override fun ruleResolved(
        valueResolver: RuleValueResolver<C, T>,
        build: RuleScope<C>.() -> Unit,
    ) {
        val rule = ruleBuilder().apply(build).build()
        values += rule.targetedBy(valueResolver)
    }

    override fun ruleScopedResolved(
        valueResolver: RuleValueResolver<C, T>,
        build: ContextRuleScope<C>.() -> Unit,
    ) {
        val rule = ruleBuilder().apply {
            @Suppress("UNCHECKED_CAST")
            (this as ContextRuleScope<C>).apply(build)
        }.build()
        values += rule.targetedBy(valueResolver)
    }

    override fun include(ruleSet: RuleSet<in C, T, C, M>) {
        values += ruleSet.rules.map { spec -> spec.rule.targetedBy(spec.value) }
    }

    override fun include(ruleSet: NamespaceRuleSet<in C, T, C, M>) {
        require(ruleSet.namespace == feature.namespace) {
            "Cannot include namespace-scoped RuleSet from ${ruleSet.namespace.id} into ${feature.namespace.id}"
        }
        values += ruleSet.rules.map { spec -> spec.rule.targetedBy(spec.value) }
    }

    /**
     * Builds and returns a `FlagDefinition` instance create type `S` with contextFn type `C`.
     * Internal method - not intended for direct use.
     *
     * @return A `FlagDefinition` instance constructed based on the current configuration.
     */
    @KonditionalInternalApi
    fun build(): FlagDefinition<T, C, M> = pendingYields
        .takeIf { it.isEmpty() }
        ?.let {
            FlagDefinition(
                feature = feature,
                bounds = values,
                defaultValue = default,
                salt = salt,
                isActive = isActive,
                rampUpAllowlist = rolloutAllowlist,
            )
        }
        ?: error(unclosedYieldingRulesErrorMessage(featureKey = feature.key, pendingYields = pendingYields))

    private fun ruleBuilder(): RuleBuilder<C> =
        RuleBuilder(ruleId = RuleId.forFeatureRule(feature.id, nextRuleOrdinal++))
}

private fun unclosedYieldingRulesErrorMessage(
    featureKey: String,
    pendingYields: Set<PendingYieldToken>,
): String =
    buildString {
        appendLine(
            "Unclosed criteria-first rule detected for feature '$featureKey': " +
                "`rule { ... }` must be completed with `yields(value)` or `yields { ... }`."
        )
        appendLine(
            "Fix: change `rule { criteria }` to `rule { criteria } yields someValue` " +
                "or `rule { criteria } yields { resolveValue() }`."
        )
        pendingYields
            .mapNotNull { it.callSite }
            .takeIf { it.isNotEmpty() }
            ?.let { callSites ->
                appendLine("Call sites:")
                callSites.forEach { appendLine("- $it") }
            }
    }
