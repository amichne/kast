package io.amichne.konditional.rules

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.context.Context
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.dsl.rules.RuleValueResolver
import io.amichne.konditional.core.dsl.rules.RuleValueScope
import io.amichne.konditional.core.registry.NamespaceRegistry
import io.amichne.konditional.internal.SerializedRuleValueType

/**
 * Represents a rule paired with its target value.
 * When the rule matches a contextFn, the paired value is returned.
 *
 * @param T The actual value type
 * @param C The contextFn type used for rule evaluation
 */
@ConsistentCopyVisibility
@KonditionalInternalApi
data class ConditionalValue<T : Any, C : Context> private constructor(
    val rule: Rule<C>,
    private val resolver: Resolver<T, C>,
    internal val serializedValueType: SerializedRuleValueType,
) {
    /**
     * Returns the static rule value.
     *
     * Deferred values are evaluated only during flag evaluation and are not
     * available as a standalone constant.
     */
    val value: T
        get() = resolver.staticValueOrNull()
            ?: error("Rule value is context-dependent and can only be resolved during evaluation.")

    internal fun resolve(
        context: C,
        registry: NamespaceRegistry,
        ownerNamespace: Namespace,
    ): T = resolver.resolve(
        context = context,
        registry = registry,
        ownerNamespace = ownerNamespace,
    )

    internal fun staticValueOrNull(): T? = resolver.staticValueOrNull()

    private sealed interface Resolver<T : Any, C : Context> {
        fun resolve(
            context: C,
            registry: NamespaceRegistry,
            ownerNamespace: Namespace,
        ): T

        fun staticValueOrNull(): T?
    }

    private data class StaticResolver<T : Any, C : Context>(
        val value: T,
    ) : Resolver<T, C> {
        override fun resolve(
            context: C,
            registry: NamespaceRegistry,
            ownerNamespace: Namespace,
        ): T = value

        override fun staticValueOrNull(): T = value
    }

    private class ContextualResolver<T : Any, C : Context>(
        private val valueResolver: RuleValueResolver<C, T>,
    ) : Resolver<T, C> {
        override fun resolve(
            context: C,
            registry: NamespaceRegistry,
            ownerNamespace: Namespace,
        ): T = RuleValueScope(
            context = context,
            evaluationRegistry = registry,
            ownerNamespace = ownerNamespace,
        ).valueResolver()

        override fun staticValueOrNull(): T? = null
    }

    companion object {
        internal fun <T : Any, C : Context> Rule<C>.targetedBy(value: T): ConditionalValue<T, C> =
            ConditionalValue(this, StaticResolver(value), SerializedRuleValueType.STATIC)

        internal fun <T : Any, C : Context> Rule<C>.targetedBy(
            valueResolver: RuleValueResolver<C, T>,
        ): ConditionalValue<T, C> =
            ConditionalValue(this, ContextualResolver(valueResolver), SerializedRuleValueType.CONTEXTUAL)

        internal fun <T : Any, C : Context> Rule<C>.targetedBySerialized(
            value: T,
            type: SerializedRuleValueType,
        ): ConditionalValue<T, C> = ConditionalValue(this, StaticResolver(value), type)
    }
}
