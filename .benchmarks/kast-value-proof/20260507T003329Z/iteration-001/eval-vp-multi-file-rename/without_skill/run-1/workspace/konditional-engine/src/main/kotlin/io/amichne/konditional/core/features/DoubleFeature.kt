package io.amichne.konditional.core.features

import io.amichne.konditional.context.Context
import io.amichne.konditional.core.Namespace

sealed interface DoubleFeature<C : Context, M : Namespace> :
    Feature<Double, C, M> {

    companion object {
        internal operator fun <C : Context, M : Namespace> invoke(
            key: String,
            module: M,
        ): DoubleFeature<C, M> =
            DoubleFeatureImpl(key, module)

        @PublishedApi
        internal data class DoubleFeatureImpl<C : Context, M : Namespace>(
            override val key: String,
            override val namespace: M,
        ) : DoubleFeature<C, M>, Identifiable.ById by Identifiable.ById(key, namespace.id)
    }
}
