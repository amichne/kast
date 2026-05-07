package io.amichne.konditional.values

import io.amichne.konditional.core.features.Identifiable.Named.NonBlank
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Stable UUID identifier for a rule.
 *
 * [RuleId] values are deterministic name-based UUIDs derived from stable rule seeds.
 * This allows rule-scoped metadata (for example, inline anonymous predicates) to
 * round-trip through serialization and resolve back to the same logical rule.
 */
@JvmInline
value class RuleId(override val value: String) : NonBlank {
    init {
        validate()
        require(isUuid(value)) { "RuleId must be a valid UUID: '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val uuidRegex: Regex = Regex(
            pattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )

        val unspecified: RuleId = fromSeed("konditional:rule:unspecified")

        fun forFeatureRule(
            featureId: FeatureId,
            ruleOrdinal: Int,
        ): RuleId = fromSeed("konditional:rule:feature:${featureId.plainId}:${ruleOrdinal.coerceAtLeast(0)}")

        fun forFeatureRuleSetRule(
            featureId: FeatureId,
            ruleOrdinal: Int,
        ): RuleId = fromSeed("konditional:rule:feature-ruleset:${featureId.plainId}:${ruleOrdinal.coerceAtLeast(0)}")

        fun forNamespaceRuleSetRule(
            namespaceId: NamespaceId,
            ruleSetSeed: String,
            ruleOrdinal: Int,
        ): RuleId = fromSeed(
            "konditional:rule:namespace-ruleset:${namespaceId.value}:$ruleSetSeed:${ruleOrdinal.coerceAtLeast(0)}",
        )

        private fun fromSeed(seed: String): RuleId = RuleId(
            UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString(),
        )

        private fun isUuid(value: String): Boolean = uuidRegex.matches(value)
    }
}
