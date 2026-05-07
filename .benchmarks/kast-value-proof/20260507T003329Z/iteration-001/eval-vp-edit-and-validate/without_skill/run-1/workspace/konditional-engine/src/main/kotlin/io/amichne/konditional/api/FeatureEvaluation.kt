package io.amichne.konditional.api

import io.amichne.konditional.context.Context
import io.amichne.konditional.core.FlagDefinition
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.evaluation.Bucketing.isInRampUp
import io.amichne.konditional.core.evaluation.Bucketing.rampUpThresholdBasisPoints
import io.amichne.konditional.core.features.Feature
import io.amichne.konditional.core.ops.Metrics
import io.amichne.konditional.core.registry.NamespaceRegistry
import io.amichne.konditional.internal.evaluation.EvaluationDiagnostics
import io.amichne.konditional.rules.ConditionalValue
import io.amichne.konditional.rules.Rule
import io.amichne.konditional.rules.targeting.Targeting
import io.amichne.konditional.rules.targeting.axesOrEmpty
import io.amichne.konditional.rules.targeting.customLeafCount
import io.amichne.konditional.rules.targeting.localesOrEmpty
import io.amichne.konditional.rules.targeting.platformsOrEmpty
import io.amichne.konditional.rules.targeting.versionRangeOrNull
import io.amichne.konditional.rules.versions.Unbounded
import java.security.MessageDigest
import kotlin.time.measureTimedValue

private const val MINIMUM_ORDINAL = 0
private const val RULE_ID_HASH_LENGTH = 8

/**
 * Evaluates this feature for the given context.
 *
 * By default, evaluates using the feature's namespace registry.
 * For testing, you can provide an explicit registry parameter.
 *
 * Example:
 * ```kotlin
 * val enabled = AppFlags.darkMode.evaluate(context)
 * ```
 *
 * @param context The evaluation context
 * @param registry The registry to use (defaults to the feature's namespace)
 * @return The evaluated value
 * @throws IllegalStateException if the feature is not registered in the registry
 */

fun <T : Any, C : Context, M : Namespace> Feature<T, C, M>.evaluate(
    context: C,
    registry: NamespaceRegistry = namespace,
): T = evaluateInternal(context, registry, mode = Metrics.Evaluation.EvaluationMode.NORMAL).value

/**
 * Explains how this feature was evaluated for the given context.
 *
 * This returns the diagnostics snapshot used by Konditional internals:
 * matched/default decision, optional rollout skip details, and computed bucket metadata.
 *
 * @param context The evaluation context
 * @param registry The registry to use (defaults to the feature's namespace)
 * @return Deterministic evaluation diagnostics for this input and registry snapshot
 * @throws IllegalStateException if the feature is not registered in the registry
 */

fun <T : Any, C : Context, M : Namespace> Feature<T, C, M>.explain(
    context: C,
    registry: NamespaceRegistry = namespace,
): EvaluationDiagnostics<T> = evaluateInternal(context, registry, mode = Metrics.Evaluation.EvaluationMode.EXPLAIN)

@PublishedApi
internal fun <T : Any, C : Context, M : Namespace> Feature<T, C, M>.evaluateInternal(
    context: C,
    registry: NamespaceRegistry,
    mode: Metrics.Evaluation.EvaluationMode,
): EvaluationDiagnostics<T> = evaluateInternal(
    context = context,
    registry = registry,
    mode = mode,
    definition = registry.flag(this),
)

@PublishedApi
internal fun <T : Any, C : Context, M : Namespace> Feature<T, C, M>.evaluateInternal(
    context: C,
    registry: NamespaceRegistry,
    mode: Metrics.Evaluation.EvaluationMode,
    definition: FlagDefinition<T, C, M>,
): EvaluationDiagnostics<T> =
    measureTimedValue { createBaseDiagnostics(context, registry, mode, definition) }.also {
        logExplainIfNeeded(it.value, registry, mode)
        recordEvaluationMetrics(it.value, registry, mode, it.duration)
    }.value

private fun <T : Any, C : Context, M : Namespace> Feature<T, C, M>.createBaseDiagnostics(
    context: C,
    registry: NamespaceRegistry,
    mode: Metrics.Evaluation.EvaluationMode,
    definition: FlagDefinition<T, C, M>,
): EvaluationDiagnostics<T> =
    when {
        registry.isAllDisabled ->
            EvaluationDiagnostics(
                namespaceId = registry.namespaceId,
                featureKey = key,
                configVersion = registry.configuration.metadata.version,
                mode = mode,
                durationNanos = 0L,
                value = declaredDefaultValueOr(definition.defaultValue),
                decision = EvaluationDiagnostics.Decision.RegistryDisabled,
            )

        !definition.isActive ->
            EvaluationDiagnostics(
                namespaceId = registry.namespaceId,
                featureKey = key,
                configVersion = registry.configuration.metadata.version,
                mode = mode,
                durationNanos = 0L,
                value = definition.defaultValue,
                decision = EvaluationDiagnostics.Decision.Inactive,
            )

        else -> createRuleDiagnostics(context, registry, mode, definition)
    }

@Suppress("UNCHECKED_CAST")
private fun <T : Any, C : Context, M : Namespace> Feature<T, C, M>.declaredDefaultValueOr(
    fallback: T,
): T =
    (namespace.declaredDefault(this) as? T) ?: fallback

private fun <T : Any, C : Context, M : Namespace> Feature<T, C, M>.createRuleDiagnostics(
    context: C,
    registry: NamespaceRegistry,
    mode: Metrics.Evaluation.EvaluationMode,
    definition: FlagDefinition<T, C, M>,
): EvaluationDiagnostics<T> {
    val trace = definition.evaluateTrace(
        context = context,
        registry = registry,
    )
    val skippedByRollout =
        trace.skippedByRampUp?.toRuleMatch(
            bucket = trace.bucket,
            featureKey = key,
            namespaceId = registry.namespaceId,
            salt = definition.salt,
            ruleOrdinal = definition.valuesByPrecedence.indexOfFirst { it === trace.skippedByRampUp },
        )
    val decision =
        trace.matched
            ?.toRuleMatch(
                bucket = trace.bucket,
                featureKey = key,
                namespaceId = registry.namespaceId,
                salt = definition.salt,
                ruleOrdinal = definition.valuesByPrecedence.indexOfFirst { it === trace.matched },
            )?.let { matched ->
                EvaluationDiagnostics.Decision.Rule(
                    matched = matched,
                    skippedByRollout = skippedByRollout,
                )
            }
            ?: EvaluationDiagnostics.Decision.Default(skippedByRollout = skippedByRollout)

    return EvaluationDiagnostics(
        namespaceId = registry.namespaceId,
        featureKey = key,
        configVersion = registry.configuration.metadata.version,
        mode = mode,
        durationNanos = 0L,
        value = trace.value,
        decision = decision,
    )
}

private fun <T : Any, C : Context> ConditionalValue<T, C>.toRuleMatch(
    bucket: Int?,
    featureKey: String,
    namespaceId: String,
    salt: String,
    ruleOrdinal: Int,
): EvaluationDiagnostics.RuleMatch<EvaluationDiagnostics.RuleExplanation> = bucket?.let { bucket ->
    rule.toExplanation(ruleId = createRuleId(namespaceId, featureKey, ruleOrdinal)).let {
        EvaluationDiagnostics.RuleMatch(
            rule = it,
            bucket = BucketInfo(
                featureKey = featureKey,
                salt = salt,
                bucket = bucket,
                rollout = it.rollout,
                thresholdBasisPoints = rampUpThresholdBasisPoints(it.rollout),
                inRollout = isInRampUp(it.rollout, bucket),
            ),
        )
    }
} ?: error("Bucket must be computed when a rule matches by criteria")

private fun <C : Context> Rule<C>.toExplanation(
    ruleId: String,
): EvaluationDiagnostics.RuleExplanation = EvaluationDiagnostics.RuleExplanation(
    note = note,
    rollout = rampUp,
    locales = targeting.localesOrEmpty(),
    platforms = targeting.platformsOrEmpty(),
    versionRange = targeting.versionRangeOrNull() ?: Unbounded,
    axes = targeting.axesOrEmpty(),
    baseSpecificity = (if (targeting.localesOrEmpty().isNotEmpty()) 1 else 0) +
        (if (targeting.platformsOrEmpty().isNotEmpty()) 1 else 0) +
        (if ((targeting.versionRangeOrNull() ?: Unbounded).hasBounds()) 1 else 0) +
        targeting.axesOrEmpty().size,
    extensionSpecificity = targeting.customLeafCount(),
    totalSpecificity = targeting.specificity(),
    extensionClassName = null,
    ruleId = ruleId,
    extensionNode = targeting.toExtensionNode(),
    conditionalContextNode = targeting.toConditionalContextNode(),
)

private fun createRuleId(namespaceId: String, featureKey: String, ruleOrdinal: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = "$namespaceId:$featureKey:${ruleOrdinal.coerceAtLeast(MINIMUM_ORDINAL)}"
    val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
    val shortHash = hash.take(RULE_ID_HASH_LENGTH).joinToString("") { "%02x".format(it) }
    return "rule::$namespaceId::$featureKey::$shortHash"
}

private fun <C : Context> Targeting.All<C>.toExtensionNode(): EvaluationDiagnostics.ExtensionNode {
    val hasExtension =
        targets.any { it is Targeting.Custom<*> || (it is Targeting.Guarded<*, *> && it.inner is Targeting.Custom<*>) }
    return if (!hasExtension) {
        EvaluationDiagnostics.ExtensionNode(EvaluationDiagnostics.ExtensionType.NONE)
    } else {
        EvaluationDiagnostics.ExtensionNode(
            type = EvaluationDiagnostics.ExtensionType.LAMBDA,
            content = toTargetingNode(),
        )
    }
}

private fun <C : Context> Targeting.All<C>.toConditionalContextNode(): EvaluationDiagnostics.ConditionalContextNode {
    val hasNarrowing = targets.any { it is Targeting.Guarded<*, *> }
    return if (!hasNarrowing) {
        EvaluationDiagnostics.ConditionalContextNode(EvaluationDiagnostics.ConditionalContextType.NONE)
    } else {
        EvaluationDiagnostics.ConditionalContextNode(
            type = EvaluationDiagnostics.ConditionalContextType.NARROWING,
            content = toTargetingNode(),
        )
    }
}

@Suppress("UNCHECKED_CAST")
private fun <C : Context> Targeting.All<C>.toTargetingNode(): EvaluationDiagnostics.TargetingNode =
    EvaluationDiagnostics.TargetingNode.All(children = targets.map { (it as Targeting<Context>).toTargetingNode() })

@Suppress("UNCHECKED_CAST")

private fun Targeting<Context>.toTargetingNode(): EvaluationDiagnostics.TargetingNode =
    when (this) {
        is Targeting.All<*> -> (this as Targeting.All<Context>).toTargetingNode()
        is Targeting.AnyOf<*> ->
            EvaluationDiagnostics.TargetingNode.AnyOf(
                children = this.targets.map { (it as Targeting<Context>).toTargetingNode() },
            )
        is Targeting.Locale -> EvaluationDiagnostics.TargetingNode.Locale(ids = ids)
        is Targeting.Platform -> EvaluationDiagnostics.TargetingNode.Platform(ids = ids)
        is Targeting.Version -> EvaluationDiagnostics.TargetingNode.Version(range = range)
        is Targeting.Axis -> EvaluationDiagnostics.TargetingNode.Axis(axisId = axisId, allowedIds = allowedIds)
        is Targeting.Custom<*> -> EvaluationDiagnostics.TargetingNode.Custom
        is Targeting.Guarded<*, *> ->
            EvaluationDiagnostics.TargetingNode.Guarded(
                child = (inner as Targeting<Context>).toTargetingNode(),
            )
    }
