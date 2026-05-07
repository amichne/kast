package io.amichne.konditional.core.dsl.rules

import io.amichne.konditional.context.Context
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.dsl.KonditionalDsl
import io.amichne.konditional.core.features.Feature

@KonditionalDsl
class RuleSet<RC : Context, T : Any, C, M : Namespace> @PublishedApi internal constructor(
    val feature: Feature<T, C, M>,
    internal val rules: List<RuleSpec<T, RC>>,
) where C : RC {
    operator fun plus(other: RuleSet<RC, T, C, M>): RuleSet<RC, T, C, M> =
        RuleSet(feature, rules + other.rules)

    companion object {
        fun <RC : Context, T : Any, C, M : Namespace> empty(
            feature: Feature<T, C, M>,
        ): RuleSet<RC, T, C, M> where C : RC =
            RuleSet(feature, emptyList())
    }
}
