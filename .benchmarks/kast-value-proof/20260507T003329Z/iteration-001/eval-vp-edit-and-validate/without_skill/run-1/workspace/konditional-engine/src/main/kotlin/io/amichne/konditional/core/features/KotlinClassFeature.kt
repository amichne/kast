package io.amichne.konditional.core.features

import io.amichne.konditional.context.Context
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.types.Konstrained

/**
 * Sealed interface for custom encodeable feature flags.
 *
 * KotlinClassFeature (despite its name) allows using any custom encodeable type as feature flag values,
 * providing structured, type-safe configuration for custom object, primitive, array,
 * and adapted value shapes.
 *
 * Example:
 * ```kotlin
 * data class PaymentConfig(
 *     val maxRetries: Int = 3,
 *     val timeout: Double = 30.0,
 *     val enabled: Boolean = true
 * ) : Konstrained.Object
 *
 * object Payments : Namespace("payments") {
 *     val PAYMENT_CONFIG by custom(default = PaymentConfig()) {
 *         rule(PaymentConfig(maxRetries = 5, timeout = 60.0)) { android() }
 *     }
 * }
 * ```
 *
 * @param T The custom type implementing [Konstrained]
 * @param C The context type used for evaluation
 * @param M The namespace this feature belongs to
 */
sealed interface KotlinClassFeature<T : Konstrained, C : Context, M : Namespace> :
    Feature<T, C, M> {

    companion object {
        /**
         * Factory function for creating KotlinClassFeature instances.
         *
         * @param key The feature key (usually the property name)
         * @param module The namespace this feature belongs to
         * @return A KotlinClassFeature instance
         */
        operator fun <T : Konstrained, C : Context, M : Namespace> invoke(
            key: String,
            module: M,
        ): KotlinClassFeature<T, C, M> =
            KotlinClassFeatureImpl(key, module)

        @PublishedApi
        internal data class KotlinClassFeatureImpl<T : Konstrained, C : Context, M : Namespace>(
            override val key: String,
            override val namespace: M,
        ) : KotlinClassFeature<T, C, M>, Identifiable.ById by Identifiable.ById(key, namespace.id)
    }
}
