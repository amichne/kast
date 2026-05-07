@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.core.dsl.rules

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.api.evaluate
import io.amichne.konditional.context.Context
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.dsl.FlagScope
import io.amichne.konditional.core.features.Feature
import io.amichne.konditional.core.registry.NamespaceRegistry

/**
 * Typed receiver used by deferred `yields { ... }` rule values.
 *
 * Instances are created only during evaluation for a rule that already matched
 * targeting and rollout constraints.
 *
 * Determinism note:
 * keep resolver logic pure and deterministic for a fixed context + registry snapshot.
 */
class RuleValueScope<C : Context> @PublishedApi internal constructor(
    val context: C,
    private val evaluationRegistry: NamespaceRegistry,
    private val ownerNamespace: Namespace,
) {
    /**
     * Evaluates [Feature] using the current rule evaluation context.
     *
     * Same-namespace features are resolved against the current evaluation registry
     * to preserve shadow/baseline semantics. Cross-namespace features use their own
     * namespace registry.
     */
    fun <T : Any, M : Namespace> Feature<T, C, M>.evaluate(): T =
        if (namespace === ownerNamespace) {
            evaluate(context = context, registry = evaluationRegistry)
        } else {
            evaluate(context = context)
        }

    /**
     * Evaluates [Feature] using invoke-style syntax in deferred `yields { ... }` resolvers.
     *
     * Equivalent to [evaluate], including same-namespace registry preservation.
     */
    operator fun <T : Any, M : Namespace> Feature<T, C, M>.invoke(): T = evaluate()
}

/**
 * Deferred rule value resolver used by criteria-first `yields { ... }`.
 */
typealias RuleValueResolver<C, T> = RuleValueScope<C>.() -> T

/**
 * Internal bridge for [FlagScope] implementations that support deferred rule values.
 */
@KonditionalInternalApi
interface ContextualYieldingScope<T : Any, C : Context> {
    fun ruleResolved(
        valueResolver: RuleValueResolver<C, T>,
        build: RuleScope<C>.() -> Unit = {},
    )

    fun ruleScopedResolved(
        valueResolver: RuleValueResolver<C, T>,
        build: ContextRuleScope<C>.() -> Unit = {},
    )
}
