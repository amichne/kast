@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.internal.serialization.models

import com.squareup.moshi.JsonClass
import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.internal.SerializedFlagRuleSpec
import io.amichne.konditional.internal.SerializedRuleValueType
import io.amichne.konditional.rules.versions.Unbounded
import io.amichne.konditional.rules.versions.VersionRange
import io.amichne.konditional.values.RuleId

/**
 * Serializable representation of a rule + value pair.
 *
 * Now uses type-safe FlagValue instead create type-erased SerializableValue,
 * and uses VersionRange directly (serialized via custom Moshi adapter).
 */
@KonditionalInternalApi
@JsonClass(generateAdapter = true)
internal data class SerializableRule(
    val value: FlagValue<*>,
    val type: SerializedRuleValueType = SerializedRuleValueType.STATIC,
    val ruleId: RuleId? = null,
    val rampUp: Double = 100.0,
    val rampUpAllowlist: Set<String> = emptySet(),
    val note: String? = null,
    val locales: Set<String> = emptySet(),
    val platforms: Set<String> = emptySet(),
    val versionRange: VersionRange? = null,
    val axes: Map<String, Set<String>> = emptyMap(),
) {
    fun <T : Any> toSpec(
        value: T,
        fallbackRuleId: RuleId,
    ): SerializedFlagRuleSpec<T> =
        SerializedFlagRuleSpec(
            value = value,
            type = type,
            ruleId = ruleId ?: fallbackRuleId,
            rampUp = rampUp,
            rampUpAllowlist = rampUpAllowlist,
            note = note,
            locales = locales,
            platforms = platforms,
            versionRange = versionRange ?: Unbounded,
            axes = axes,
        )

    companion object {
        fun fromSpec(rule: SerializedFlagRuleSpec<*>): SerializableRule {
            val value = requireNotNull(rule.value) { "SerializedFlagRuleSpec must not hold a null value" }

            return SerializableRule(
                value = FlagValue.from(value),
                type = rule.type,
                ruleId = rule.ruleId,
                rampUp = rule.rampUp,
                rampUpAllowlist = rule.rampUpAllowlist,
                note = rule.note,
                locales = rule.locales,
                platforms = rule.platforms,
                versionRange = rule.versionRange,
                axes = rule.axes,
            )
        }
    }
}
