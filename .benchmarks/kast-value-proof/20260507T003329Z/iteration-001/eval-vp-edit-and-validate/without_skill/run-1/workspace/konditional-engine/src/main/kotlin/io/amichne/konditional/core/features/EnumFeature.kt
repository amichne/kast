package io.amichne.konditional.core.features

import io.amichne.konditional.context.Context
import io.amichne.konditional.core.Namespace

/**
 * Feature type for user-defined enum values.
 * Provides compile-time type safety for enum-typed feature flags.
 *
 * @param E The specific enum type
 * @param C The context type for evaluation
 * @param M The namespace type for isolation
 */
sealed interface EnumFeature<E : Enum<E>, C : Context, M : Namespace> :
    Feature<E, C, M> {

    companion object {
        internal operator fun <E : Enum<E>, C : Context, M : Namespace> invoke(
            key: String,
            module: M,
        ): EnumFeature<E, C, M> =
            EnumFeatureImpl(key, module)

        @PublishedApi
        internal data class EnumFeatureImpl<E : Enum<E>, C : Context, M : Namespace>(
            override val key: String,
            override val namespace: M,
        ) : EnumFeature<E, C, M>, Identifiable.ById by Identifiable.ById(key, namespace.id)
    }
}
