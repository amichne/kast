@file:OptIn(ExperimentalStdlibApi::class)

package io.amichne.konditional.internal

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.context.Context
import io.amichne.konditional.context.RampUp
import io.amichne.konditional.core.FlagDefinition
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.features.Feature
import io.amichne.konditional.core.id.HexId
import io.amichne.konditional.rules.ConditionalValue.Companion.targetedBySerialized
import io.amichne.konditional.rules.Rule
import io.amichne.konditional.rules.targeting.Targeting
import io.amichne.konditional.rules.targeting.axesOrEmpty
import io.amichne.konditional.rules.targeting.localesOrEmpty
import io.amichne.konditional.rules.targeting.platformsOrEmpty
import io.amichne.konditional.rules.targeting.versionRangeOrNull
import io.amichne.konditional.rules.versions.Unbounded
import io.amichne.konditional.rules.versions.VersionRange
import io.amichne.konditional.values.RuleId

/**
 * Internal contracts for encoding/decoding flag definitions across Konditional modules.
 *
 * These are deliberately shaped around primitives and stable public types so sibling modules can
 * integrate without `-Xfriend-paths`, while still keeping these contracts explicitly non-public.
 */
@KonditionalInternalApi
data class SerializedFlagDefinitionMetadata(
    val salt: String = "v1",
    val isActive: Boolean = true,
    val rampUpAllowlist: Set<String> = emptySet(),
)

@KonditionalInternalApi
@Suppress("LongParameterList")
data class SerializedFlagRuleSpec<T : Any>(
    val value: T,
    val type: SerializedRuleValueType = SerializedRuleValueType.STATIC,
    val ruleId: RuleId = RuleId.unspecified,
    val rampUp: Double = 100.0,
    val rampUpAllowlist: Set<String> = emptySet(),
    val note: String? = null,
    val locales: Set<String> = emptySet(),
    val platforms: Set<String> = emptySet(),
    val versionRange: VersionRange? = null,
    val axes: Map<String, Set<String>> = emptyMap(),
)

@KonditionalInternalApi
enum class SerializedRuleValueType {
    STATIC,
    CONTEXTUAL,
}

@KonditionalInternalApi
fun <T : Any, C : Context, M : Namespace> flagDefinitionFromSerialized(
    feature: Feature<T, C, M>,
    defaultValue: T,
    rules: List<SerializedFlagRuleSpec<T>>,
    metadata: SerializedFlagDefinitionMetadata = SerializedFlagDefinitionMetadata(),
): FlagDefinition<T, C, M> =
    FlagDefinition(
        feature = feature,
        bounds =
            rules.map { spec ->
                val leaves = buildList<Targeting<C>> {
                    if (spec.locales.isNotEmpty())
                        add(Targeting.locale(spec.locales))
                    if (spec.platforms.isNotEmpty())
                        add(Targeting.platform(spec.platforms))
                    spec.versionRange
                        ?.takeIf { it != Unbounded }
                        ?.let { add(Targeting.version(it)) }
                    spec.axes.forEach { (axisId, allowedIds) ->
                        add(Targeting.Axis(axisId, allowedIds))
                    }
                }
                Rule<C>(
                    rampUp = RampUp.of(spec.rampUp),
                    rampUpAllowlist = spec.rampUpAllowlist.mapTo(linkedSetOf()) { HexId(it) },
                    note = spec.note,
                    targeting = Targeting.All(leaves),
                    ruleId = spec.ruleId,
                ).targetedBySerialized(spec.value, spec.type)
            },
        defaultValue = defaultValue,
        salt = metadata.salt,
        isActive = metadata.isActive,
        rampUpAllowlist = metadata.rampUpAllowlist.mapTo(linkedSetOf()) { HexId(it) },
    )

@KonditionalInternalApi
fun FlagDefinition<*, *, *>.toSerializedMetadata(): SerializedFlagDefinitionMetadata =
    SerializedFlagDefinitionMetadata(
        salt = salt,
        isActive = isActive,
        rampUpAllowlist = rampUpAllowlist.mapTo(linkedSetOf()) { it.id },
    )

@KonditionalInternalApi
fun FlagDefinition<*, *, *>.toSerializedRules(): List<SerializedFlagRuleSpec<Any>> =
    values.map { cv ->
        val staticValue = cv.staticValueOrNull()
        val type = cv.serializedValueType
        val value = staticValue ?: defaultValue
        val targeting = cv.rule.targeting
        SerializedFlagRuleSpec(
            value = value,
            type = type,
            ruleId = cv.rule.ruleId,
            rampUp = cv.rule.rampUp.value,
            rampUpAllowlist = cv.rule.rampUpAllowlist.mapTo(linkedSetOf()) { it.id },
            note = cv.rule.note,
            locales = targeting.localesOrEmpty(),
            platforms = targeting.platformsOrEmpty(),
            versionRange = targeting.versionRangeOrNull() ?: Unbounded,
            axes = targeting.axesOrEmpty(),
        )
    }
