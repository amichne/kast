package io.amichne.konditional.core.dsl.rules

import io.amichne.konditional.context.Context
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.dsl.KonditionalDsl

/**
 * Namespace-scoped reusable rules that are context-bound but not feature-bound.
 *
 * These rules are authored once for a namespace and can be included by any feature of the
 * same namespace that shares compatible value/context types.
 */
@KonditionalDsl
class NamespaceRuleSet<RC : Context, T : Any, C, M : Namespace> @PublishedApi internal constructor(
    val namespace: M,
    internal val rules: List<RuleSpec<T, RC>>,
) where C : RC {
    operator fun plus(other: NamespaceRuleSet<RC, T, C, M>): NamespaceRuleSet<RC, T, C, M> =
        NamespaceRuleSet(namespace, rules + other.rules)

    companion object {
        fun <RC : Context, T : Any, C, M : Namespace> empty(
            namespace: M,
        ): NamespaceRuleSet<RC, T, C, M> where C : RC =
            NamespaceRuleSet(namespace, emptyList())
    }
}
