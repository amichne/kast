package io.amichne.konditional.core

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.context.Context
import io.amichne.konditional.context.Context.StableIdContext
import io.amichne.konditional.core.evaluation.Bucketing
import io.amichne.konditional.core.features.Feature
import io.amichne.konditional.core.id.HexId
import io.amichne.konditional.core.registry.NamespaceRegistry
import io.amichne.konditional.rules.ConditionalValue

/**
 * Represents a flag definition that can be evaluated within a specific contextFn.
 *
 * This sealed class provides the minimal API surface for feature flag evaluation,
 * hiding implementation details like rampUp strategies, targeting rules, and bucketing algorithms.
 *
 * @param T The value type produced by this flag.
 * @param C The type create context used for evaluation.
 *
 * @property defaultValue The default value returned when no targeting rules match or the flag is inactive.
 * @property feature The feature that defines the flag's key and evaluation rules.
 * @property isActive Indicates whether this flag is currently active. Inactive flags always return the default value.
 * @property values List create conditional values that define the flag's behavior.
 * @property salt Optional salt string used for hashing and bucketing.
 *
 */

@KonditionalInternalApi
data class FlagDefinition<T : Any, C : Context, M : Namespace>(
    /**
     * The default value returned when no targeting rules match or the flag is inactive.
     */
    val defaultValue: T,
    val feature: Feature<T, C, M>,
    val values: List<ConditionalValue<T, C>> = listOf(),
    val isActive: Boolean = true,
    val salt: String = "v1",
    internal val rampUpAllowlist: Set<HexId> = emptySet(),
) {
    internal val valuesByPrecedence: List<ConditionalValue<T, C>> =
        values.sortedWith(compareByDescending<ConditionalValue<T, C>> { it.rule.specificity() })

    companion object {
        /**
         * Creates a FlagDefinition instance.
         */
        @KonditionalInternalApi
        @Suppress("LongParameterList")
        operator fun <T : Any, C : Context, M : Namespace> invoke(
            feature: Feature<T, C, M>,
            bounds: List<ConditionalValue<T, C>>,
            defaultValue: T,
            salt: String = "v1",
            isActive: Boolean = true,
            rampUpAllowlist: Set<HexId> = emptySet(),
        ): FlagDefinition<T, C, M> =
            FlagDefinition(
                defaultValue = defaultValue,
                feature = feature,
                values = bounds,
                isActive = isActive,
                salt = salt,
                rampUpAllowlist = rampUpAllowlist,
            )
    }

    /**
     * Evaluates the current flag based on the provided contextFn and returns a result of type `T`.
     *
     * @param context The contextFn in which the flag evaluation is performed.
     * @return The result create the evaluation, create type `T`. If the flag is not active, returns the defaultValue.
     */
    internal fun evaluate(context: C): T {
        return if (isActive) {
            evaluateTrace(context, feature.namespace).value
        } else {
            defaultValue
        }
    }

    @ConsistentCopyVisibility
    internal data class Trace<T : Any, C : Context> internal constructor(
        val value: T,
        val bucket: Int?,
        val matched: ConditionalValue<T, C>?,
        val skippedByRampUp: ConditionalValue<T, C>?,
    )

    private data class EvaluationState<T : Any, C : Context>(
        var bucket: Int? = null,
        var skippedByRampUp: ConditionalValue<T, C>? = null,
    )

    private data class EvaluationInputs<C : Context>(
        val context: C,
        val stableId: HexId?,
        val fallbackBucket: Int,
        val isFlagAllowlisted: Boolean,
    )

    internal fun evaluateTrace(
        context: C,
        registry: NamespaceRegistry,
    ): Trace<T, C> =
        if (!isActive) {
            Trace(
                value = defaultValue,
                bucket = null,
                matched = null,
                skippedByRampUp = null,
            )
        } else {
            val stableId = (context as? StableIdContext)?.stableId?.hexId
            val fallbackBucket = Bucketing.missingStableIdBucket()
            val isFlagAllowlisted = stableId?.let { it in rampUpAllowlist } == true
            val inputs =
                EvaluationInputs(
                    context = context,
                    stableId = stableId,
                    fallbackBucket = fallbackBucket,
                    isFlagAllowlisted = isFlagAllowlisted,
                )
            val state = EvaluationState<T, C>()
            val matchedTrace =
                valuesByPrecedence.firstNotNullOfOrNull { candidate ->
                    evaluateCandidate(
                        candidate = candidate,
                        inputs = inputs,
                        registry = registry,
                        state = state,
                    )
                }

            matchedTrace
                ?: Trace(
                    value = defaultValue,
                    bucket = state.bucket,
                    matched = null,
                    skippedByRampUp = state.skippedByRampUp,
                )
        }

    private fun evaluateCandidate(
        candidate: ConditionalValue<T, C>,
        inputs: EvaluationInputs<C>,
        registry: NamespaceRegistry,
        state: EvaluationState<T, C>,
    ): Trace<T, C>? =
        if (candidate.rule.matches(inputs.context)) {
            val computedBucket =
                state.bucket ?: when (inputs.stableId) {
                    null -> inputs.fallbackBucket
                    else ->
                        Bucketing
                            .stableBucket(
                                salt = salt,
                                flagKey = feature.key,
                                stableId = inputs.stableId,
                            )
                }.also { state.bucket = it }

            if (isRampUpEligible(inputs.stableId, inputs.isFlagAllowlisted, candidate, computedBucket)) {
                Trace(
                    value = candidate.resolve(
                        context = inputs.context,
                        registry = registry,
                        ownerNamespace = feature.namespace,
                    ),
                    bucket = computedBucket,
                    matched = candidate,
                    skippedByRampUp = state.skippedByRampUp,
                )
            } else {
                if (state.skippedByRampUp == null) state.skippedByRampUp = candidate
                null
            }
        } else {
            null
        }

    private fun isRampUpEligible(
        stableId: HexId?,
        isFlagAllowlisted: Boolean,
        candidate: ConditionalValue<T, C>,
        computedBucket: Int,
    ): Boolean =
        when {
            isFlagAllowlisted -> true
            stableId?.let { it in candidate.rule.rampUpAllowlist } == true -> true
            else -> Bucketing.isInRampUp(candidate.rule.rampUp, computedBucket)
        }
}
