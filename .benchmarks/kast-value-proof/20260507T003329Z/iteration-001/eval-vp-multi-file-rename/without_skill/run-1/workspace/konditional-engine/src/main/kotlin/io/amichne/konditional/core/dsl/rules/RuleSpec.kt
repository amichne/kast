package io.amichne.konditional.core.dsl.rules

import io.amichne.konditional.context.Context
import io.amichne.konditional.core.dsl.KonditionalDsl
import io.amichne.konditional.rules.Rule

/**
 * A feature-scoped set of rules that can be composed with other rule sets.
 *
 * Rule sets are contravariant in context to allow composing contributors written
 * against supertypes of the feature's context type.
 */
@ConsistentCopyVisibility
@KonditionalDsl
data class RuleSpec<out T : Any, in C : Context> @PublishedApi internal constructor(
    val value: T,
    val rule: Rule<C>,
)
