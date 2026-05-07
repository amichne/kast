package io.amichne.konditional.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class KonditionalRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "konditional"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            UnclosedCriteriaFirstRule(config),
        ),
    )
}

